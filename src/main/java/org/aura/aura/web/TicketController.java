package org.aura.aura.web;

import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.TicketClassificationService;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolverService;
import org.aura.aura.web.dto.ClassificationResponse;
import org.aura.aura.web.dto.ClassifyTicketRequest;
import org.aura.aura.web.dto.ResolutionResponse;
import org.aura.aura.web.dto.ResolveTicketRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// Driving adapter: translates HTTP <-> domain. Contains NO business logic.
// Litmus test: delete this class, drive the services from a test -> zero behavior lost.
@RestController
@RequestMapping("/api/v1/tickets")  // path-based versioning: version visible in URL & tool-friendly
class TicketController {

    private final ResolverService resolverService; // constructor injection -> final, testable
    private final TicketClassificationService classificationService;

    TicketController(ResolverService resolverService, TicketClassificationService classificationService) {
        this.resolverService = resolverService;
        this.classificationService = classificationService;
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
        Resolution resolution = resolverService.resolve(request.message());
        return ResolutionResponse.from(ticketId, resolution, ClassificationResponse.from(classification));
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
