package org.aura.aura.web.dto;

import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.TicketCategory;
import org.aura.aura.classification.TicketIntent;
import org.aura.aura.classification.TicketUrgency;

// Outbound contract for a classification. Flattens ClassificationResult's two-level shape
// (classification + our review verdict) into one wire object — clients shouldn't need to
// know we keep the model's answer and our trust verdict separate internally.
public record ClassificationResponse(
        TicketCategory category,
        TicketUrgency urgency,
        TicketIntent intent,
        double confidence,
        // Surfaced deliberately: a client that hides this flag will happily automate on
        // fallback data. Making it part of the contract makes ignoring it a choice.
        boolean needsHumanReview
) {
    public static ClassificationResponse from(ClassificationResult result) {
        return new ClassificationResponse(
                result.classification().category(),
                result.classification().urgency(),
                result.classification().intent(),
                result.classification().confidence(),
                result.needsHumanReview()
        );
    }
}
