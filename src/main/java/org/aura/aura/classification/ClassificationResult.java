package org.aura.aura.classification;

// Wrapper instead of returning TicketClassification directly: needsHumanReview is OUR
// verdict (derived from stop_reason + semantic validation), not the model's. Keeping it
// outside TicketClassification keeps the model contract pure — the model can't be asked
// to grade its own trustworthiness.
//
// Day 10 added `reason`. It is ADDITIVE: needsHumanReview keeps its exact previous behavior and
// remains the field callers act on. `reason` answers the separate question "why", which the boolean
// alone cannot — needsHumanReview=true previously meant either "Claude was down" or "the model was
// unsure", two situations that demand opposite treatment when measuring quality. See ReviewReason.
//
// Invariant: reason == NONE if and only if needsHumanReview == false.
public record ClassificationResult(
        TicketClassification classification,
        boolean needsHumanReview,
        ReviewReason reason
) {}
