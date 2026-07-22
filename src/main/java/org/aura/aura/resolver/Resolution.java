package org.aura.aura.resolver;

import java.util.List;

// TWO ESCALATION CHANNELS, deliberately kept apart (full rationale on ResolutionStatus):
//   status == ESCALATED_TO_HUMAN -> DEPENDENCY HEALTH. Single writer: the Resilience4j fallback.
//   escalate                     -> the AGENT'S BUSINESS JUDGMENT, copied straight from ResolverOutput.
// A healthy call that decides a human is needed is (RESOLVED, escalate=true); an outage is
// (ESCALATED_TO_HUMAN, escalate=true). Collapsing the two into one field is precisely the
// one-value-many-meanings defect Day 10 removed: grading escalation off `status` could only ever
// have "passed" when the Anthropic API was down.
//
// sourcesUsed stays OURS, never the model's — ResolverService derives it from the KbEntry hits it
// actually retrieved, which is why it is absent from ResolverOutput's schema.
public record Resolution(String answer, List<String> sourcesUsed, ResolutionStatus status, boolean escalate) {

    // ADR-014/ADR-018 marker. An ESCALATED_TO_HUMAN result is an AVAILABILITY answer (Claude was
    // unhealthy), not a KNOWLEDGE answer — so the Day 9 cache must never store it, or we'd keep
    // escalating tickets for the whole TTL after Anthropic recovers. CachedResolutionService gates
    // cache.put on this. Note it keys off `status`, NOT `escalate`: a model-chosen escalation IS a
    // knowledge answer (the same ticket deserves the same escalation tomorrow) and stays cacheable.
    public boolean isEscalatedFallback() {
        return status == ResolutionStatus.ESCALATED_TO_HUMAN;
    }
}
// Day 6 extends this (category/urgency/intent). Day 24 extends it (tokens/cost/model).
// Day 8 added `status`: the resolve path can now end in a degraded ESCALATED_TO_HUMAN outcome
// (circuit breaker open) that a caller must be able to tell apart from a normal RESOLVED answer.
// Day 10 added `escalate`: the model's own escalation verdict, which until now existed only as
// prose inside the reply text and so could not be measured, routed, or asserted on.
// Returning String today would mean refactoring every caller then. Pay the seam now.
