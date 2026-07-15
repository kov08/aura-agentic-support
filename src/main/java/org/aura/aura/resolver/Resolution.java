package org.aura.aura.resolver;

import java.util.List;

public record Resolution(String answer, List<String> sourcesUsed, ResolutionStatus status) {

    // ADR-014/ADR-018 marker. An ESCALATED_TO_HUMAN result is an AVAILABILITY answer (Claude was
    // unhealthy), not a KNOWLEDGE answer — so the Day 9 cache must never store it, or we'd keep
    // escalating tickets for the whole TTL after Anthropic recovers. CachedResolutionService gates
    // cache.put on this.
    public boolean isEscalatedFallback() {
        return status == ResolutionStatus.ESCALATED_TO_HUMAN;
    }
}
// Day 6 extends this (category/urgency/intent). Day 24 extends it (tokens/cost/model).
// Day 8 added `status`: the resolve path can now end in a degraded ESCALATED_TO_HUMAN outcome
// (circuit breaker open) that a caller must be able to tell apart from a normal RESOLVED answer.
// Returning String today would mean refactoring every caller then. Pay the seam now.
