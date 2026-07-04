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
import org.aura.aura.resolver.ResolverService;
import org.aura.aura.web.dto.ClassificationResponse;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    private final AnthropicClient client;
    private final ResolverService resolverService;
    private final TicketClassificationService classificationService;
    private final Executor sseExecutor;

    public TicketStreamingService(
            AnthropicClient client,
            ResolverService resolverService,
            TicketClassificationService classificationService,
            @Qualifier(StreamingAsyncConfig.SSE_EXECUTOR) Executor sseExecutor) {
        this.client = client;
        this.resolverService = resolverService;
        this.classificationService = classificationService;
        this.sseExecutor = sseExecutor;
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
        StringBuilder fullText = new StringBuilder(); // accumulated server-side: logging now, Redis persistence Day 9
        long inputTokens = 0L;
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
            MessageCreateParams params = resolverService.buildStreamingParams(request.message());
            try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
                // Iterator, not forEach: send() throws checked IOException, which a Consumer lambda
                // can't propagate. The explicit loop lets that IOException bubble to the catch below.
                Iterator<RawMessageStreamEvent> events = stream.stream().iterator();
                while (events.hasNext()) {
                    RawMessageStreamEvent event = events.next();

                    // message_start carries input-token usage — capture it, nothing to emit yet.
                    if (event.messageStart().isPresent()) {
                        inputTokens = event.messageStart().get().message().usage().inputTokens();
                    }

                    // content_block_delta with a text delta -> one "delta" frame. Accumulate the
                    // same text server-side for logging/persistence.
                    Optional<TextDelta> textDelta = event.contentBlockDelta().flatMap(d -> d.delta().text());
                    if (textDelta.isPresent()) {
                        String piece = textDelta.get().text();
                        fullText.append(piece);
                        emitter.send(sse(SseEvents.DELTA, new DeltaEvent(piece)));
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
            emitter.send(sse(SseEvents.DONE, new DoneEvent(stopReason, inputTokens, outputTokens, elapsedMs)));
            log.info("SSE [{}] done — stop_reason={}, in={}, out={}, elapsedMs={}, chars={}",
                    ticketId, stopReason, inputTokens, outputTokens, elapsedMs, fullText.length());
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

    // Every frame is named + JSON so a client dispatches on the event name and parses one body
    // shape throughout. MediaType.APPLICATION_JSON drives Jackson serialization of the payload.
    private static SseEmitter.SseEventBuilder sse(String name, Object data) {
        return SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON);
    }
}
