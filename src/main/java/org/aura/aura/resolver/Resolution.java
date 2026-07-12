package org.aura.aura.resolver;

import java.util.List;

public record Resolution(String answer, List<String> sourcesUsed, ResolutionStatus status) {}
// Day 6 extends this (category/urgency/intent). Day 24 extends it (tokens/cost/model).
// Day 8 added `status`: the resolve path can now end in a degraded ESCALATED_TO_HUMAN outcome
// (circuit breaker open) that a caller must be able to tell apart from a normal RESOLVED answer.
// Returning String today would mean refactoring every caller then. Pay the seam now.
