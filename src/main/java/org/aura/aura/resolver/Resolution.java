package org.aura.aura.resolver;

import org.aura.aura.retrieval.SourceRef;

import java.util.List;

// TWO ESCALATION CHANNELS, deliberately kept apart (full rationale on ResolutionStatus):
//   status == ESCALATED_TO_HUMAN -> DEPENDENCY HEALTH. Single writer: the Resilience4j fallback.
//   escalate                     -> the AGENT'S BUSINESS JUDGMENT, copied straight from ResolverOutput.
// A healthy call that decides a human is needed is (RESOLVED, escalate=true); an outage is
// (ESCALATED_TO_HUMAN, escalate=true). Collapsing the two into one field is precisely the
// one-value-many-meanings defect Day 10 removed: grading escalation off `status` could only ever
// have "passed" when the Anthropic API was down.
//
// Day 14 renamed sourcesUsed -> sourcesProvided, and the rename is the point rather than tidying.
// "Used" was a claim about what the model DID with the context, which nothing here can observe;
// "provided" is a record of what was put in front of it, which is exactly what this list contains.
// The old name invited the reading that an entry means the answer was grounded in that chunk — and
// under Day 4's three-entry keyword KB that reading was nearly harmless, because retrieval almost
// never fired. With real semantic search every ticket retrieves something, so the distinction
// between "shown" and "used" now carries weight on every single response.
//
// The list stays OURS, never the model's: ContextBlockAssembler derives it from the surviving chunk
// set, which is why it is absent from ResolverOutput's schema (one writer per field).
public record Resolution(String answer, List<SourceRef> sourcesProvided, ResolutionStatus status,
                         boolean escalate) {

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
// Day 14 widened the source list from List<String> to List<SourceRef>: an id alone could not carry
// the distance, and a citation with no distance cannot tell a confident answer apart from a
// desperate one — cosine distance is RELATIVE, so retrieval always returns a "best" match.
// Returning String today would mean refactoring every caller then. Pay the seam now.
