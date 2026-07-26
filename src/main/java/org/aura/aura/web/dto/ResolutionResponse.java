package org.aura.aura.web.dto;

import org.aura.aura.resolver.Resolution;
import java.util.List;

// Outbound contract. Nothing reaches the client unless it is a field here.
// Deliberately omits tokensUsed/model -> those are operational data (logs & metrics, Day 24),
// so internal cost/routing details never leak onto the wire.
public record ResolutionResponse(
        String ticketId,
        String resolutionText,
        String outcome,           // the ResolutionStatus name: RESOLVED or ESCALATED_TO_HUMAN (Day 11)
        List<String> sourcesUsed, // trust signal: empty list on a confident answer is a smell
        // Day 6: the classification that preceded this resolution rides along in the
        // response. Today it's informational (and the routing hook for Day 7+); exposing
        // it now means clients integrate against the final shape once, not twice.
        ClassificationResponse classification
) {
    // Mapping lives next to the shape it produces; trivially unit-testable.
    // A test that hands this a Resolution carrying tokensUsed and asserts it is ABSENT
    // locks the exposure decision so a future careless edit can't silently undo it.
    //
    // public (not package-private): TicketController lives in the sibling package
    // org.aura.aura.web, and Java package access does not span web -> web.dto.
    public static ResolutionResponse from(String ticketId, Resolution resolution, ClassificationResponse classification) {
        return new ResolutionResponse(
                ticketId,
                resolution.answer(),  // Resolution's accessor for the resolved text
                // Day 11: map the REAL transport status onto the wire (was hardcoded "RESOLVED"). This is
                // what lets a client — and the Day 11 integration tests — tell a normal answer apart from
                // a degraded ESCALATED_TO_HUMAN fallback during an Anthropic outage. status is the
                // single-writer dependency-health channel (see ResolutionStatus); the model's own
                // escalate verdict remains a separate concern surfaced elsewhere.
                resolution.status().name(),
                resolution.sourcesUsed(),
                classification
        );
    }
}
