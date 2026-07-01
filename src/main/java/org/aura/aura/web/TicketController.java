package org.aura.aura.web;

import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolverService;
import org.aura.aura.web.dto.ResolutionResponse;
import org.aura.aura.web.dto.ResolveTicketRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

// Driving adapter: translates HTTP <-> domain. Contains NO business logic.
// Litmus test: delete this class, drive ResolverService from a test -> zero behavior lost.
@RestController
@RequestMapping("/api/v1/tickets")  // path-based versioning: version visible in URL & tool-friendly
class TicketController {

    private final ResolverService resolverService; // constructor injection -> final, testable

    TicketController(ResolverService resolverService) {
        this.resolverService = resolverService;
    }

    // POST, not GET: resolving has side effects (a paid model call) and must not be cached as a safe read.
    @PostMapping("/{ticketId}/resolve")
    ResolutionResponse resolve(
            @PathVariable String ticketId,                   // in the path now; ticket LOOKUP arrives Phase 4
            @Valid @RequestBody ResolveTicketRequest request // @Valid runs constraints BEFORE the body executes
    ) {
        // Thin: validate (done) -> delegate -> map -> return. Implicit 200 on the returned body.
        Resolution resolution = resolverService.resolve(request.message());
        return ResolutionResponse.from(ticketId, resolution);
    }
}
