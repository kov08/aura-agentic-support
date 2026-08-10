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
import org.aura.aura.resolver.EscalationCause;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolverOutput;
import org.aura.aura.resolver.ResolverService;
import org.aura.aura.retrieval.ContextBlock;
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
        // Day 16 (Decision 4): THE REPLY IS BUFFERED, not forwarded as it generates.
        //
        // Day 10 accumulated the envelope AND forwarded the reply incrementally, because nothing then
        // stood between the model and the customer. The grounding gates do, and they cannot run early:
        // `grounded` is the LAST field the model emits (verdict-last, deliberately — see
        // ResolverOutput), so the verdict does not exist until the final token. Any character shown
        // before that is unverified at the moment it is shown.
        //
        // That leaves exactly two shapes and no third. Forward-then-retract means telling a customer
        // that the paragraph they just read is withdrawn — worse than a spinner, and this file already
        // refused to do it (see parseEnvelope). Buffer means the perceived-latency win Day 7 bought is
        // gone on this path. Buffering is the ruling, and the cost is stated rather than softened: on
        // any GATED path this endpoint is now the blocking endpoint with a richer frame protocol.
        //
        // It is kept rather than deleted because the wire contract stays valid for integrators, the
        // transport stays exercised, and Phase 4's routing can hand genuine streaming back to the
        // tickets that owe no citations — which is the population the wide-denominator over-refusal
        // number identified. When that lands, the change here is to emit inside the loop again.
        StringBuilder envelope = new StringBuilder();
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
            //     The block is held in a LOCAL rather than passed inline, because Day 16 gave it a
            //     second reader: G4 checks the model's citations against this exact set. Retrieving
            //     again at gate time would be a second billable embedding AND a second search whose
            //     result could differ, so the gate would be validating against documents the model
            //     was never shown — the same one-retrieval-per-request rule Decision 4 established
            //     for the cache key on the blocking path.
            ContextBlock context = retrievalService.retrieve(request.message());
            MessageCreateParams params = resolverService.buildStreamingParams(request.message(), context);
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
                    // ACCUMULATED ONLY. Nothing is emitted from inside this loop any more: the gates
                    // that decide whether this answer may be shown at all cannot run until the last
                    // token has arrived, so there is no point in the stream at which forwarding a
                    // fragment would be a decision anyone had made.
                    Optional<TextDelta> textDelta = event.contentBlockDelta().flatMap(d -> d.delta().text());
                    textDelta.ifPresent(delta -> envelope.append(delta.text()));

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

            // (d1) GATE 0, and it is a stronger gate here than on the blocking path — because the
            //      reply was buffered, a payload we cannot read costs us nothing that has already
            //      been shown. Before Day 16 this path logged the failure and carried on, having
            //      already streamed the text; now an unreadable envelope means grounding cannot be
            //      verified, and an answer whose grounding is unknown does not get sent.
            //
            //      NOT retried, unlike the blocking path's G0. resolve() is a pure read that can be
            //      re-issued whole; a stream cannot be resumed, and re-opening one would re-bill the
            //      entire generation. The asymmetry is deliberate and is the cost of this transport.
            Resolution resolution = parseEnvelope(ticketId, envelope.toString())
                    // (d2) G3 + G4, the SAME implementation the blocking path runs — not a copy.
                    //      This is the seam whose absence let this endpoint ship ungated.
                    .map(output -> resolverService.applyGroundingGates(output, context))
                    .orElseGet(() -> Resolution.escalatedToHuman(EscalationCause.OUTPUT_UNUSABLE));

            // (d3) NOW the text goes out — one delta carrying the whole reply, then the terminal
            //      frame. One frame rather than artificial chunking: a client that concatenates
            //      deltas is unaffected, and faking incremental arrival for text that is already
            //      complete would be theatre.
            //
            //      An escalation is emitted the same way, as ordinary reply text, because that is
            //      what it is — a business outcome at HTTP 200 (ADR-013), not an ERROR frame. The
            //      ERROR frame stays reserved for a transport failure, where there IS no answer.
            emitter.send(sse(SseEvents.DELTA, new DeltaEvent(resolution.answer())));

            // DoneEvent's existing fields are untouched — stop_reason, billing tokens, latency — and
            // the cache figures stay in the log line only, same "ops data doesn't hit the wire"
            // discipline as ResolutionResponse. `outcome` is ADDITIVE, which is what finally settles
            // the note this file has carried since Day 10 ("Day 16 owns exposing escalation on the
            // wire"): a buffered stream that could not tell a grounded answer from an escalation
            // would have made the gates invisible to the only client that reads this endpoint.
            emitter.send(sse(SseEvents.DONE, new DoneEvent(
                    stopReason, inputTokens, outputTokens, elapsedMs, resolution.status().name())));
            log.info("SSE [{}] done — stop_reason={}, outcome={}, cause={}, escalate={}, in={}, out={}, "
                            + "cacheCreate={}, cacheRead={}, elapsedMs={}, envelopeChars={}",
                    ticketId, stopReason, resolution.status(), resolution.escalationCause(),
                    resolution.escalate(), inputTokens, outputTokens,
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

    // Day 16 changed what an empty return here COSTS, and it is worth recording because the old
    // comment argued the opposite conclusion from the same facts.
    //
    // While the reply streamed as it generated, a malformed tail arrived after the customer had
    // already read the answer: retrying would have duplicated output and an error frame would have
    // contradicted what they just watched appear, so the only honest move was to log it and let the
    // stream end. The failure cost us the escalate verdict and nothing else.
    //
    // Buffered, nothing has been shown yet — so the caller can and does treat an unreadable envelope
    // as a grounding failure and escalate. Same method, same log line, and the opposite downstream
    // decision, purely because of when it now runs.
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
