package org.aura.aura.classification;

// Wrapper instead of returning TicketClassification directly: needsHumanReview is OUR
// verdict (derived from stop_reason + semantic validation), not the model's. Keeping it
// outside TicketClassification keeps the model contract pure — the model can't be asked
// to grade its own trustworthiness.
public record ClassificationResult(
        TicketClassification classification,
        boolean needsHumanReview
) {}
