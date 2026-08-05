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
