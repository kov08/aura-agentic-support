package org.aura.aura.resolver;

/**
 * How a {@link Resolution} was reached. Two states exist today:
 *
 * <ul>
 *   <li>{@link #RESOLVED} — Claude answered the ticket; {@code sourcesUsed} is the grounding receipt.</li>
 *   <li>{@link #ESCALATED_TO_HUMAN} — degraded outcome: the Claude dependency was unhealthy (circuit
 *       breaker OPEN), so instead of erroring we handed the ticket to a human. This is a
 *       <em>business-valid</em> result, not a failure (Day 5: escalation still returns HTTP 200).</li>
 * </ul>
 *
 * <p>This is deliberately a small, explicit enum rather than a boolean: Day 16 grows the escalation
 * taxonomy (angry-customer, refund-over-threshold, low-confidence, ...) and this is the seam it
 * extends. The marker is what makes a degraded answer countable — Day 24 builds the "how often do we
 * degrade" metric off the WARN log that carries it.
 */
public enum ResolutionStatus {
    RESOLVED,
    ESCALATED_TO_HUMAN
}
