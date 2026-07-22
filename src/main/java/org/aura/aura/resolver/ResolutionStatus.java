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
 *
 * <p><b>This enum is a DEPENDENCY-HEALTH signal and nothing else.</b> It has exactly one writer —
 * {@code ResolverService.escalateToHuman}, the Resilience4j fallback — so {@code ESCALATED_TO_HUMAN}
 * means "Claude was unhealthy", never "the agent judged that a human is needed". The agent's business
 * judgment lives on its own channel, {@link Resolution#escalate()}, sourced from
 * {@link ResolverOutput#escalate()}.
 *
 * <p>Keeping the two apart is load-bearing, not stylistic. Before Day 10 the resolve() success path
 * returned {@code RESOLVED} unconditionally, so a reply that said "I'm escalating this to a
 * specialist" was indistinguishable from one that resolved the ticket outright — the escalation
 * decision existed only as prose and could not be measured or routed on. Grading it off this enum
 * would have been worse than useless: the only way to observe {@code ESCALATED_TO_HUMAN} is an
 * Anthropic outage, which the eval harness excludes from scoring as DEGRADED. Two meanings, one
 * field, mutually exclusive observations. Hence two channels.
 */
public enum ResolutionStatus {
    RESOLVED,
    ESCALATED_TO_HUMAN
}
