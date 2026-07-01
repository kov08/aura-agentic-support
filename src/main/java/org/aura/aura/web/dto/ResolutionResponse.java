package org.aura.aura.web.dto;

import org.aura.aura.resolver.Resolution;
import java.util.List;

// Outbound contract. Nothing reaches the client unless it is a field here.
// Deliberately omits tokensUsed/model -> those are operational data (logs & metrics, Day 24),
// so internal cost/routing details never leak onto the wire.
public record ResolutionResponse(
        String ticketId,
        String resolutionText,
        String outcome,           // "RESOLVED" today; real escalation states arrive Day 16
        List<String> sourcesUsed  // trust signal: empty list on a confident answer is a smell
) {
    // Mapping lives next to the shape it produces; trivially unit-testable.
    // A test that hands this a Resolution carrying tokensUsed and asserts it is ABSENT
    // locks the exposure decision so a future careless edit can't silently undo it.
    //
    // public (not package-private): TicketController lives in the sibling package
    // org.aura.aura.web, and Java package access does not span web -> web.dto.
    public static ResolutionResponse from(String ticketId, Resolution resolution) {
        return new ResolutionResponse(
                ticketId,
                resolution.answer(),  // Resolution's accessor for the resolved text
                "RESOLVED",            // TODO Day 16: map real outcome/escalation flag
                resolution.sourcesUsed()
        );
    }
}
