package org.aura.aura.classification;

/**
 * WHY a ticket was routed to human review — as data, not as a WARN string.
 *
 * <p>Every fallback path in {@link TicketClassificationService} already knows its own cause. Before
 * Day 10 that knowledge was formatted into a log line and discarded, leaving four genuinely different
 * situations sharing one byte-identical return value: {@code (OTHER, MEDIUM, GET_INFORMATION, 0.0)}
 * with {@code needsHumanReview=true}. Only the log message differed. That is the same
 * one-value-many-meanings defect Day 10 removed from the resolver (see
 * {@link org.aura.aura.resolver.ResolutionStatus}) — cured here proactively rather than after it bit us.
 *
 * <p>The distinction that forced it is a scoring one. {@link #DEPENDENCY_UNAVAILABLE} is a DEGRADED
 * run: the model never answered, so those labels are our fallback constants, and scoring them would
 * measure nothing but this file. {@link #LOW_CONFIDENCE} is its opposite — a real, model-driven
 * outcome that must be scored normally, because "the model was honestly unsure" is exactly the kind
 * of judgment an eval exists to measure. An eval that could not tell those two apart would silently
 * credit an outage as a calibration result.
 *
 * <p>Note this is five constants rather than the four originally sketched: the {@code stop_reason}
 * gate covers three distinct failures, and naming them separately is the whole point of the enum.
 * Day 24's degradation metrics consume this.
 */
public enum ReviewReason {

    /** Not a fallback: the model answered and cleared the confidence floor. */
    NONE,

    /**
     * The Resilience4j path — breaker OPEN, or a transport failure (429 / 5xx / IO). No model answer
     * exists at all. The ONLY value that means "degraded"; every other non-NONE value is a real outcome.
     */
    DEPENDENCY_UNAVAILABLE,

    /** The model answered and was honest about being unsure — below {@code CONFIDENCE_FLOOR}. */
    LOW_CONFIDENCE,

    /** {@code stop_reason=refusal}: the model declined to classify. Content arrives empty. */
    REFUSED,

    /** {@code stop_reason=max_tokens}: the 256-token cost fuse blew and truncated the JSON. */
    TRUNCATED,

    /** An {@code end_turn} with no text block, or a {@code stop_reason} we don't recognise. */
    MALFORMED_RESPONSE
}
