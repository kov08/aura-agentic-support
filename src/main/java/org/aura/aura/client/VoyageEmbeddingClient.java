package org.aura.aura.client;

import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.config.VoyageProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Voyage embeddings transport: text in, vectors out.
 *
 * <h2>The asymmetric routing surface</h2>
 * There is one HTTP call underneath and TWO public methods on top, and that is the whole design.
 * {@link #embedDocuments} runs the premium {@code documentModel} on the ONE-TIME offline ingestion
 * lane; {@link #embedQuery} runs the economy {@code queryModel} on the PER-TICKET online lane. The
 * cost asymmetry is the reason: a document is embedded once ever, so quality is cheap there, while a
 * query is embedded on every single customer ticket, so quality is multiplied by traffic. Spending
 * the expensive model where the cost is amortised and the cheap one where it recurs is the Day 20
 * cost thesis — "match the model tier to the lane's economics" — applied one layer below the
 * classifier/resolver split it usually describes.
 *
 * <p>Both the model AND the {@code input_type} are derived from the method the caller chose. A single
 * {@code embed(text, model, type)} method would be smaller and would let one call site pair the
 * document model with the query input type — a mistake that produces perfectly valid vectors that
 * simply retrieve worse, with no error anywhere. Two methods and a private core make that pairing
 * unrepresentable.
 *
 * <h2>Retry ownership</h2>
 * {@code @Retry(name = "voyage")} is the ONLY retry in this stack. The RestClient is built with no
 * retry of its own, exactly as the Anthropic SDK is pinned to {@code maxRetries(0)} (ADR-012/016) —
 * two retry layers multiply rather than add, and 3 × 3 = 9 calls per request is how a rate-limit blip
 * becomes a rate-limit outage. Resilience4j is the single owner, and its policy is visible in
 * application.yml rather than buried in a builder.
 *
 * <p>Retrying is safe on BOTH lanes here because embedding is a pure read: the same input returns the
 * same vector and nothing is created, charged twice, or moved. That property is not general — it is
 * worth naming precisely because the Day 17 refund tool will NOT have it, and its retry policy cannot
 * be copied from this one.
 */
@Slf4j
@Service
public class VoyageEmbeddingClient {

    // One dependency (the Voyage API), one instance name shared by every policy bound to it in
    // application.yml — the same convention as "anthropicApi".
    private static final String VOYAGE = "voyage";

    private static final String EMBEDDINGS_PATH = "/v1/embeddings";

    /**
     * Inputs per request. The provider's documented ceiling is <b>1,000</b>; this is 128.
     *
     * <p>The gap is not timidity, it is blast radius. A batch is one retry unit and one failure unit:
     * a 429 on a 1,000-input request re-sends 1,000 inputs, and a permanent failure loses all of them.
     * It is also one memory unit — 1,000 × 1,024 floats is 4MB of vectors held while the response is
     * parsed. 128 keeps a full document in one call for anything under ~250KB of markdown while
     * keeping each failure cheap.
     */
    static final int MAX_INPUTS_PER_CALL = 128;

    /**
     * Estimated tokens per request. The provider's cap is <b>per model</b>: 120K for
     * {@code voyage-4-large} (and the other large/domain models), 320K for {@code voyage-4}, 1M for
     * the {@code -lite} models.
     *
     * <p>60K is half of the TIGHTEST of those, and the tightest is the one that matters because this
     * method runs the DOCUMENT lane — which is {@code voyage-4-large}. Sizing against the model
     * actually configured would be more precise and would turn a config change into a silent
     * over-cap: point {@code voyage.document-model} at a model with a smaller budget and a batch that
     * used to fit starts failing whole. One conservative number, chosen against the worst case we can
     * be pointed at, has no such edge.
     *
     * <p>The 2× margin pays for the estimate being an estimate. The ~4-chars-per-token proxy
     * UNDER-counts for anything dense in punctuation, code, or non-Latin script — precisely the
     * content whose real token count could overshoot — so the headroom absorbs a tokenizer that
     * disagrees with us by up to a factor of two before the provider does the rejecting.
     */
    static final int MAX_ESTIMATED_TOKENS_PER_CALL = 60_000;

    private static final int CHARS_PER_TOKEN = 4;

    private final RestClient http;
    private final VoyageProperties props;

    public VoyageEmbeddingClient(VoyageProperties props) {
        this.props = props;

        // EXPLICIT timeouts, always, from validated configuration. Never inherit a library's defaults:
        // Spring Data Redis taught this the expensive way on Day 9, where the un-set command timeout
        // defaulted to 60 SECONDS and turned an unreachable cache into a hung request. A client with no
        // timeout is not "fast by default", it is "unbounded by default" — and the failure only shows up
        // when the dependency is already having a bad day.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());   // bounds TCP establishment
        factory.setReadTimeout(props.readTimeout());         // bounds the wait for the response body

        this.http = RestClient.builder()
                .baseUrl(props.baseUrl())
                // The key is guaranteed non-blank by @NotBlank on VoyageProperties, so there is no
                // "missing key" branch to write here — that failure already happened at startup.
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .requestFactory(factory)
                .build();
    }

    /**
     * The OFFLINE lane: embed a batch of knowledge-base chunks with the premium document model.
     * Called once per document version by the Day 15 ingestion pipeline, never on a customer request.
     *
     * @return one vector per input, in the SAME order as the input list
     */
    @Retry(name = VOYAGE)
    public List<float[]> embedDocuments(List<String> inputs) {
        return embed(inputs, props.documentModel(), documentInputType());
    }

    /**
     * The OFFLINE lane, batched: embed an arbitrary number of chunk texts, splitting them into as
     * many HTTP requests as the provider's caps require.
     *
     * <p>{@link #embedDocuments} takes whatever list it is handed and sends it as ONE request, which
     * makes the caller responsible for knowing the caps. That was tolerable when the only caller was
     * a loader with a hardcoded 64 and a corpus of three files. It is the wrong contract for a
     * pipeline whose input size is "however many chunks a document happens to have", so the knowledge
     * moves here — to the class that already knows which model it is calling and therefore which cap
     * applies.
     *
     * @return the vectors, one per input, in INPUT ORDER across batch boundaries — plus the number of
     *         HTTP calls that produced them, which is what {@link org.aura.aura.ingest.IngestReport}
     *         turns into the idempotency proof
     */
    @Retry(name = VOYAGE)
    public BatchedEmbeddings embedBatched(List<String> inputs) {
        List<List<String>> batches = batches(inputs);

        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (List<String> batch : batches) {
            // The PRIVATE core, not this.embedDocuments(...) — and that is not a shortcut, it is the
            // self-invocation trap being avoided rather than walked into. @Retry works by an AOP
            // proxy that wraps this bean from the outside, so it only intercepts calls that cross the
            // bean boundary; `this.embedDocuments(batch)` never leaves the object, the proxy never
            // sees it, and the annotation would silently do nothing. Exactly the mechanism that makes
            // @Transactional useless on a self-called method, which is why IngestionPipeline uses
            // TransactionTemplate instead of annotating its per-document loop.
            //
            // So the retry lives on THIS method, where an external caller does cross the proxy. The
            // consequence is that the retry unit is the whole call rather than one batch: a transient
            // failure on batch 3 re-sends batches 1 and 2 as well. Accepted, with eyes open — a
            // knowledge-base document chunks to single digits and is therefore one batch in practice,
            // so the two policies are the same policy at this corpus's document sizes. The document
            // that makes them differ would need ~128 chunks, i.e. roughly 250KB of markdown in one
            // file, and the fix then is to split the file, not to complicate this.
            vectors.addAll(embed(batch, props.documentModel(), documentInputType()));
        }

        if (vectors.size() != inputs.size()) {
            // Unreachable unless embed() breaks its own contract, which it already checks. Asserted
            // again because the failure it guards is invisible: every chunk paired with a neighbour's
            // vector loads cleanly, queries cleanly, and cites the wrong passage forever.
            throw new IllegalStateException("batched embedding returned " + vectors.size()
                    + " vectors for " + inputs.size() + " inputs");
        }
        return new BatchedEmbeddings(List.copyOf(vectors), batches.size());
    }

    /**
     * The result of {@link #embedBatched}: the vectors, and what they cost in round-trips.
     *
     * @param vectors one per input, in input order
     * @param calls   HTTP requests actually issued on the attempt that succeeded. Retries are not
     *                counted — see {@code IngestReport.embeddingCalls} for why the number is defined
     *                that way
     */
    public record BatchedEmbeddings(List<float[]> vectors, int calls) {
    }

    /**
     * Splits inputs into request-sized batches. Package-private and static so the sizing rule can be
     * unit-tested as arithmetic, with no client, no properties and no HTTP.
     *
     * <p>Greedy: keep adding to the current batch until the next input would breach either cap, then
     * start a new one. An input that on its own exceeds the token budget still gets sent — alone —
     * because the alternative is silently dropping it, and the chunker has already capped chunk size
     * far below anything that could realistically hit this.
     */
    static List<List<String>> batches(List<String> inputs) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int tokens = 0;

        for (String input : inputs) {
            int cost = estimateTokens(input);
            boolean wouldBreach = current.size() >= MAX_INPUTS_PER_CALL
                    || tokens + cost > MAX_ESTIMATED_TOKENS_PER_CALL;
            if (!current.isEmpty() && wouldBreach) {
                batches.add(List.copyOf(current));
                current.clear();
                tokens = 0;
            }
            current.add(input);
            tokens += cost;
        }
        if (!current.isEmpty()) batches.add(List.copyOf(current));
        return List.copyOf(batches);
    }

    // Ceiling division on the SAME ~4-characters-per-token English proxy the chunker uses for its
    // size cap and kb_chunks.token_count records. It is an approximation, and the safety factor on
    // MAX_ESTIMATED_TOKENS_PER_CALL is what pays for it being one.
    private static int estimateTokens(String text) {
        return Math.ceilDiv(text.length(), CHARS_PER_TOKEN);
    }

    /**
     * The {@code input_type} {@link #embedDocuments} actually sends.
     *
     * <h2>Why this is a method and not a comment</h2>
     * Day 14's lane-flip drill caught the canary's failure message asserting {@code /query} while the
     * client had just sent {@code /document} — because the lane in that message was a literal in a
     * format string, i.e. a statement of INTENT rather than a report of what happened. A guard whose
     * diagnosis is a hard-coded belief is a guard that lies in exactly the failure it exists to catch.
     *
     * <p>Routing the call above and the caller's report through ONE expression is what fixes that:
     * flip the constant here and the message flips with it, because there is no second place for the
     * two to disagree. This is the same one-writer-per-field rule the source ledger follows, applied
     * to a diagnostic.
     */
    public EmbeddingInputType documentInputType() {
        return EmbeddingInputType.DOCUMENT;
    }

    /** The {@code input_type} {@link #embedQuery} actually sends — see {@link #documentInputType}. */
    public EmbeddingInputType queryInputType() {
        return EmbeddingInputType.QUERY;
    }

    /**
     * The ONLINE lane: embed one customer query with the economy query model. Called on every ticket,
     * so this is the method whose latency and cost are multiplied by traffic.
     */
    @Retry(name = VOYAGE)
    public float[] embedQuery(String query) {
        return embed(List.of(query), props.queryModel(), queryInputType()).getFirst();
    }

    private List<float[]> embed(List<String> inputs, String model, EmbeddingInputType inputType) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("embed called with no input — nothing to embed");
        }

        EmbeddingRequest request = new EmbeddingRequest(inputs, model, inputType.wireValue());
        EmbeddingResponse response;
        try {
            response = http.post()
                    .uri(EMBEDDINGS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    // TRANSIENT ALLOWLIST, evaluated FIRST. Only these statuses are declared retryable:
                    // 408 (the server itself timed out), 429 (rate limited — the canonical "try again
                    // later"), and any 5xx (the provider is unwell).
                    .onStatus(VoyageEmbeddingClient::isTransient, (req, res) -> {
                        throw new VoyageTransientException(
                                "Voyage transient failure: HTTP " + res.getStatusCode() + " on " + model);
                    })
                    // Everything else that is an error falls here — 400, 401, 422, and every status
                    // this code has never seen. That DEFAULT is the point: an unknown failure is NOT
                    // retried. A denylist ("retry unless it's a 400") would fail OPEN, quietly retrying
                    // whatever the provider invents next; this fails closed, which is the safe
                    // direction and the same rule the Anthropic retry allowlist follows.
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new VoyagePermanentException(
                                "Voyage permanent failure: HTTP " + res.getStatusCode() + " on " + model
                                        + " — this will not succeed on retry");
                    })
                    .body(EmbeddingResponse.class);
        } catch (ResourceAccessException e) {
            // No HTTP status ever arrived: connect timeout, connection reset. Same class of event as a
            // 5xx from the caller's point of view — the provider did not answer — so it joins the
            // transient allowlist rather than needing a category of its own.
            throw new VoyageTransientException(
                    "Voyage transport failure (connect/read timeout or reset) on " + model, e);
        } catch (RestClientException e) {
            // VERIFIED behaviour, and the exact Day 11 trap repeating on a different client: a timeout
            // that strikes while the BODY is being read does NOT surface as ResourceAccessException. The
            // headers already arrived, so Spring is past the transport stage and inside response
            // extraction, and the socket timeout is reported as a plain RestClientException ("Error
            // while extracting response…"). Mapping only the obvious exception type would have left the
            // most realistic outage shape — a provider that accepts the request and then stalls —
            // classified as permanent and never retried.
            //
            // The discriminator is an IOException anywhere in the cause chain. That separates a stalled
            // socket from a MALFORMED body cleanly under Jackson 3, where parse failures are
            // RuntimeExceptions rather than IOExceptions: a body we received but cannot understand is
            // our problem, is not retryable, and still propagates as-is below.
            if (hasIoCause(e)) {
                throw new VoyageTransientException(
                        "Voyage transport failure (connect/read timeout or reset) on " + model, e);
            }
            throw e;
        }

        if (response == null || response.data() == null || response.data().size() != inputs.size()) {
            // A 200 whose body does not carry one vector per input is not retryable — the request was
            // accepted and answered, just not with what the contract promises. Failing loudly here
            // prevents a silent off-by-one alignment between chunks and vectors downstream, which
            // would corrupt every citation without ever throwing.
            throw new VoyagePermanentException("Voyage returned "
                    + (response == null || response.data() == null ? "no data" : response.data().size())
                    + " vectors for " + inputs.size() + " inputs");
        }

        log.info("voyage embed — model={}, inputType={}, inputs={}, tokens={}",
                model, inputType.wireValue(), inputs.size(),
                response.usage() == null ? -1 : response.usage().totalTokens());

        // Sort by index rather than trusting arrival order: the API documents `index` precisely because
        // the array order is not part of the contract, and a mis-ordered batch would attach every
        // vector to the wrong chunk — again, silently.
        return response.data().stream()
                .sorted(Comparator.comparingInt(EmbeddingResponse.Datum::index))
                .map(EmbeddingResponse.Datum::embedding)
                .toList();
    }

    private static boolean isTransient(HttpStatusCode status) {
        return status.value() == 408 || status.value() == 429 || status.is5xxServerError();
    }

    // Walks the cause chain rather than checking getCause() once: the timeout is wrapped at least
    // twice by the time it reaches us. The same shape as AnthropicTransientFailures.isReadTimeout.
    private static boolean hasIoCause(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) return true;
        }
        return false;
    }
}
