package org.aura.aura.streaming;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextDelta;
import lombok.extern.slf4j.Slf4j;
import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.TicketClassificationService;
import org.aura.aura.resolver.ResolverOutput;
import org.aura.aura.resolver.ResolverService;
import org.aura.aura.retrieval.RetrievalService;
import org.aura.aura.web.dto.ClassificationResponse;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.Executor;

// Server-Sent Events resolution: same classify-then-resolve pipeline as the blocking endpoint,
// but the answer is pushed token-by-token so a user sees words appear instead of staring at a
// spinner for the whole Sonnet generation. The perceived-latency win is the entire point.
@Slf4j
@Service
public class TicketStreamingService {

    // How much of a malformed envelope to put in the ERROR log. Bounded on purpose: enough to see
    // where generation went wrong, not enough to dump an entire reply into the log file.
    private static final int RAW_TAIL_CHARS = 200;

    private final AnthropicClient client;
    private final ResolverService resolverService;
    private final RetrievalService retrievalService;
    private final TicketClassificationService classificationService;
    private final Executor sseExecutor;
    // Boot's configured mapper (same discipline as ResolutionCache) — never `new ObjectMapper()`.
    private final ObjectMapper objectMapper;

    public TicketStreamingService(
            AnthropicClient client,
            ResolverService resolverService,
            RetrievalService retrievalService,
            TicketClassificationService classificationService,
            @Qualifier(StreamingAsyncConfig.SSE_EXECUTOR) Executor sseExecutor,
            ObjectMapper objectMapper) {
        this.client = client;
        this.resolverService = resolverService;
        this.retrievalService = retrievalService;
        this.classificationService = classificationService;
        this.sseExecutor = sseExecutor;
        this.objectMapper = objectMapper;
    }

    // Returns IMMEDIATELY with an open emitter; the actual work runs on sseExecutor. The controller
    // thread (a servlet thread) must not block here — it hands the emitter back to Spring, which
    // keeps the HTTP response open while the pump thread writes frames into it.
    public SseEmitter resolveStreaming(String ticketId, ResolveTicketRequest request) {
        SseEmitter emitter = new SseEmitter(StreamingAsyncConfig.SSE_TIMEOUT_MS);

        // Lifecycle callbacks. The real upstream cleanup is the try-with-resources in the pump
        // (closing the Anthropic stream stops token generation/billing); these just observe and,
        // on timeout, ensure the emitter is finalized so the container releases the connection.
        emitter.onCompletion(() -> log.info("SSE [{}] completed", ticketId));
        emitter.onTimeout(() -> {
            log.warn("SSE [{}] timed out after {} ms — completing", ticketId, StreamingAsyncConfig.SSE_TIMEOUT_MS);
            emitter.complete();
        });
        emitter.onError(throwable -> log.warn("SSE [{}] transport error: {}", ticketId, throwable.toString()));

        sseExecutor.execute(() -> pump(ticketId, request, emitter));
        return emitter;
    }

    // The pump: runs entirely on an sseExecutor thread. Every exit path MUST complete the emitter,
    // otherwise the connection (and its pump thread) hangs until the 120s timeout.
    private void pump(String ticketId, ResolveTicketRequest request, SseEmitter emitter) {
        long startNanos = System.nanoTime();
        // Day 10: what arrives on the wire is now the RAW structured-output envelope
        // ({"reply":"...","escalate":...}), not customer prose. Two consumers, deliberately separate:
        //   envelope  — accumulated verbatim, parsed ONCE at end-of-stream for schema validity + escalate.
        //   extractor — unwraps the reply text incrementally so the customer still sees words appear
        //               as they generate. Waiting for the envelope to complete before forwarding
        //               anything would hand back the entire perceived-latency win Day 7 bought.
        StringBuilder envelope = new StringBuilder();
        StreamingReplyExtractor replyExtractor = new StreamingReplyExtractor();
        long inputTokens = 0L;
        long cacheCreationInputTokens = 0L;  // ADR-020: prompt-cache observability, same fields as the blocking path
        long cacheReadInputTokens = 0L;
        long outputTokens = 0L;
        String stopReason = null;

        try {
            // (a) Classify FIRST, blocking. Code consumes this, not a human, so there's nothing to
            //     stream — and the service already falls back internally and never throws, so no
            //     guard is needed around it.
            ClassificationResult classification = classificationService.classify(request.message());

            // (b) First frame out is the classification, so a client can route/label the ticket
            //     before the answer text begins.
            emitter.send(sse(SseEvents.CLASSIFICATION, ClassificationResponse.from(classification)));

            // (c) Open the streaming resolution call in try-with-resources. close() is what cancels
            //     the upstream generation on ANY exit (normal, error, or client disconnect) — that's
            //     what stops paying for tokens the client will never receive.
            //     Day 14: retrieval happens HERE, on the pump thread, and the block is passed in.
            //     The streaming path deliberately does NOT go through CachedResolutionService (SSE
            //     response caching is still a parking-lot item), so it owns its own retrieval call —
            //     but it calls the same RetrievalService with the same k, the same budget and the
            //     same dedup, so a streamed answer is grounded identically to a blocking one. Only
            //     the transport differs, which has been the rule for this pair since Day 7.
            MessageCreateParams params = resolverService.buildStreamingParams(
                    request.message(), retrievalService.retrieve(request.message()));
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
                // Iterator, not forEach: send() throws checked IOException, which a Consumer lambda
                // can't propagate. The explicit loop lets that IOException bubble to the catch below.
                Iterator<RawMessageStreamEvent> events = stream.stream().iterator();
                while (events.hasNext()) {
                    RawMessageStreamEvent event = events.next();

                    // message_start carries input-token usage — capture it, nothing to emit yet.
                    // ADR-020: the same message_start usage block also carries the prompt-cache
                    // figures; cacheReadInputTokens > 0 here means the static system-prefix hit
                    // Anthropic's ephemeral cache. Optionals -> orElse(0L) on an uncached call.
                    if (event.messageStart().isPresent()) {
                        var usage = event.messageStart().get().message().usage();
                        inputTokens = usage.inputTokens();
                        cacheCreationInputTokens = usage.cacheCreationInputTokens().orElse(0L);
                        cacheReadInputTokens = usage.cacheReadInputTokens().orElse(0L);
                    }

                    // content_block_delta carries a fragment of the JSON envelope — NOT customer text.
                    // Keep the raw fragment for the end-of-stream parse, but forward only what the
                    // extractor unwraps; sending the raw piece here is what would paint
                    // {"reply":"I'm sorry it's tak across the customer's screen.
                    Optional<TextDelta> textDelta = event.contentBlockDelta().flatMap(d -> d.delta().text());
                    if (textDelta.isPresent()) {
                        String rawPiece = textDelta.get().text();
                        envelope.append(rawPiece);

                        String customerPiece = replyExtractor.accept(rawPiece);
                        // Skip empty results rather than emitting a zero-length frame: while the
                        // envelope's scaffolding streams past, the extractor legitimately has nothing
                        // to hand over, and a client shouldn't have to filter meaningless deltas.
                        if (!customerPiece.isEmpty()) {
                            emitter.send(sse(SseEvents.DELTA, new DeltaEvent(customerPiece)));
                        }
                    }

                    // message_delta carries the final stop_reason and the cumulative output usage.
                    if (event.messageDelta().isPresent()) {
                        var messageDelta = event.messageDelta().get();
                        Optional<StopReason> reason = messageDelta.delta().stopReason();
                        if (reason.isPresent()) {
                            stopReason = reason.get().asString();
                        }
                        outputTokens = messageDelta.usage().outputTokens();
                    }
                }
            }

            // (d) Clean end. stop_reason==max_tokens arrives here as truncation DATA, not an error —
            //     the answer we streamed is valid, just possibly cut short; the client decides.
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

            // (d1) Parse the completed envelope exactly once. This is the streaming twin of the
            //      blocking path's stop_reason gate — the schema-validity check — and the ONLY place
            //      this path can learn the escalate verdict, since the model emits that field after
            //      the reply text and no mid-stream frame could have carried it.
            Optional<ResolverOutput> output = parseEnvelope(ticketId, envelope.toString());
            Boolean escalate = output.map(ResolverOutput::escalate).orElse(null);

            // DoneEvent stays the client wire contract (stop_reason + billing tokens + latency); the
            // cache figures are OPERATIONAL data and stay in the log line only, never leaking onto the
            // SSE frame — same "ops data doesn't hit the wire" discipline as ResolutionResponse.
            // `escalate` joins that operational set deliberately: the SSE event protocol stays
            // byte-identical to Day 7's, and Day 16 owns exposing escalation on the wire.
            emitter.send(sse(SseEvents.DONE, new DoneEvent(stopReason, inputTokens, outputTokens, elapsedMs)));
            log.info("SSE [{}] done — stop_reason={}, escalate={}, in={}, out={}, cacheCreate={}, cacheRead={}, elapsedMs={}, envelopeChars={}",
                    ticketId, stopReason, escalate, inputTokens, outputTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, elapsedMs, envelope.length());
            emitter.complete();

        } catch (IOException disconnected) {
            // (f) send() failed because the client's socket is gone. STOP pumping: exiting this catch
            //     runs the try-with-resources close() above, which cancels the upstream Anthropic
            //     stream and stops token generation/billing. Complete quietly — no error frame,
            //     there's no one left to read it.
            log.info("SSE [{}] client disconnected — upstream cancelled", ticketId);
            emitter.complete();

        } catch (Exception upstream) {
            // (e) Anthropic/API failure mid-stream (the SDK throws unchecked). Deliver ONE error
            //     frame in the shared RFC 9457 shape, then finish. Deliberately NO retry: some of
            //     the answer may already be on the wire, and replaying would duplicate text.
            log.error("SSE [{}] upstream failure during streaming", ticketId, upstream);
            try {
                emitter.send(sse(SseEvents.ERROR, ErrorEvent.upstreamFailure()));
            } catch (IOException alsoGone) {
                // Client vanished too — nothing left to tell. Fall through to complete().
                log.info("SSE [{}] could not deliver error frame — client already gone", ticketId);
            }
            emitter.complete();
        }
    }

    // Deliberately NO invented recovery on a malformed envelope (Day 8's rule: retries happen only
    // before the first byte). By the time this runs the customer has already received the reply text
    // — it streamed as it was generated — so retrying would duplicate output and an error frame would
    // contradict what they just watched appear. What a failure here actually means is that the model
    // truncated or the transport corrupted the tail, which costs us the escalate verdict and nothing
    // else. Log it loudly for diagnosis and terminate exactly as before.
    //
    // The tail is bounded and is OUR generated reply text, not the customer's ticket — the Day 9 PII
    // rule (log the hash, never the ticket) is not weakened here.
    private Optional<ResolverOutput> parseEnvelope(String ticketId, String envelope) {
        try {
            return Optional.of(objectMapper.readValue(envelope, ResolverOutput.class));
        } catch (Exception malformed) {
            log.error("SSE [{}] resolver envelope failed schema validation — escalate verdict lost. rawTail={}",
                    ticketId, tail(envelope), malformed);
            return Optional.empty();
        }
    }

    private static String tail(String envelope) {
        return envelope.length() <= RAW_TAIL_CHARS
                ? envelope
                : envelope.substring(envelope.length() - RAW_TAIL_CHARS);
    }

    // Every frame is named + JSON so a client dispatches on the event name and parses one body
    // shape throughout. MediaType.APPLICATION_JSON drives Jackson serialization of the payload.
    private static SseEmitter.SseEventBuilder sse(String name, Object data) {
        return SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
    }
}
