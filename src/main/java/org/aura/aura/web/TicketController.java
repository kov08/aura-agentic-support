package org.aura.aura.web;

import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.TicketClassificationService;
import org.aura.aura.resolver.CachedResolutionService;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.streaming.TicketStreamingService;
import org.aura.aura.web.dto.ClassificationResponse;
import org.aura.aura.web.dto.ClassifyTicketRequest;
import org.aura.aura.web.dto.ResolutionResponse;
import org.aura.aura.web.dto.ResolveTicketRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// Driving adapter: translates HTTP <-> domain. Contains NO business logic.
// Litmus test: delete this class, drive the services from a test -> zero behavior lost.
@RestController
@RequestMapping("/api/v1/tickets")  // path-based versioning: version visible in URL & tool-friendly
class TicketController {

    // Day 9: the blocking /resolve path goes through the cache-aside bean (ADR-018), not
    // ResolverService directly — a hit skips the paid Sonnet call entirely. The streaming path
    // still uses ResolverService (response caching is a parking-lot item for SSE).
    private final CachedResolutionService cachedResolutionService; // constructor injection -> final, testable
    private final TicketClassificationService classificationService;
    private final TicketStreamingService streamingService;

    TicketController(CachedResolutionService cachedResolutionService,
                     TicketClassificationService classificationService,
                     TicketStreamingService streamingService) {
        this.cachedResolutionService = cachedResolutionService;
        this.classificationService = classificationService;
        this.streamingService = streamingService;
    }

    // POST, not GET: resolving has side effects (a paid model call) and must not be cached as a safe read.
    @PostMapping("/{ticketId}/resolve")
    ResolutionResponse resolve(
            @PathVariable String ticketId,                   // in the path now; ticket LOOKUP arrives Phase 4
            @Valid @RequestBody ResolveTicketRequest request // @Valid runs constraints BEFORE the body executes
    ) {
        // Classify FIRST, then resolve: the classification is cheap (Haiku) relative to
        // the resolution (Sonnet), and running it up front is what lets Day 7+ route,
        // prioritize, or short-circuit before paying for the expensive call. Today the
        // result only rides along in the response — the wiring point already exists.
        ClassificationResult classification = classificationService.classify(request.message());
        Resolution resolution = cachedResolutionService.resolve(request);
        return ResolutionResponse.from(ticketId, resolution, ClassificationResponse.from(classification));
    }

    // Streaming twin of /resolve: same classify-then-resolve pipeline, but the answer is pushed
    // token-by-token over Server-Sent Events (produces text/event-stream) instead of returned as
    // one JSON blob. Returning an SseEmitter tells Spring to keep the response open and hand it to
    // the pump thread; this controller method returns almost immediately.
    //
    // @Valid still runs FIRST, on the servlet thread, BEFORE the emitter exists — so a bad request
    // is rejected as a normal application/problem+json 400 by the Day 5 advice, never as a
    // half-opened stream. The two error formats stay cleanly separated: pre-stream failures are
    // REST ProblemDetail, mid-stream failures are the "error" SSE frame.
    @PostMapping(value = "/{ticketId}/resolve/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter resolveStream(
            @PathVariable String ticketId,
            @Valid @RequestBody ResolveTicketRequest request
    ) {
        return streamingService.resolveStreaming(ticketId, request);
    }

    // Classification as its own endpoint: lets triage tooling (dashboards, queue routers)
    // label a ticket without paying for a full resolution. No ticketId in the path — this
    // is a stateless judgement on a message, not an action on a stored ticket.
    @PostMapping("/classify")
    ClassificationResponse classify(@Valid @RequestBody ClassifyTicketRequest request) {
        // Thin: validate (done) -> delegate -> map -> return. Errors fall through to the
        // shared @RestControllerAdvice, same as the resolve flow.
        return ClassificationResponse.from(classificationService.classify(request.message()));
    }
}
