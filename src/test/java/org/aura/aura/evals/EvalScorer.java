package org.aura.aura.evals;

import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.ReviewReason;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolutionStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Grades one ticket's live output against its label. A PURE function: no Spring, no I/O, no clock —
 * so it is exhaustively unit-testable (Day 8 habit) and the runner is the only thing that ever talks
 * to the network. All string matching is case-insensitive.
 *
 * <p>Grading philosophy (D3): structured fields are graded STRICTLY (exact enum match, boolean
 * escalate, set-based sources); reply prose is graded only by the sparse must/mustNot substring rules
 * the label carries. The scorer never reads the reply for anything but those rules.
 *
 * <p>Two escalation channels are read from two different places, never conflated: the model's
 * business judgment is {@link Resolution#escalate()} (graded as {@code escalateMatch}); a Resilience4j
 * fallback is {@link Resolution#status()} == ESCALATED_TO_HUMAN (marks the resolver stage DEGRADED and
 * grades nothing). Likewise a classifier {@link ReviewReason#DEPENDENCY_UNAVAILABLE} marks the
 * classifier stage degraded — but a LOW_CONFIDENCE fallback does NOT: that is a real, model-driven
 * outcome scored normally, so an honestly-unsure classification still counts against the label.
 */
public final class EvalScorer {

    // Characters of reply text to show on each side of a mustNot match, so a violation in the report
    // reads in context instead of as a bare fragment.
    private static final int CONTEXT_RADIUS = 30;

    public TicketScore score(EvalTicket ticket, ClassificationResult classification, Resolution resolution) {
        ExpectedResult expected = ticket.expected();

        boolean classifierDegraded = classification.reason() == ReviewReason.DEPENDENCY_UNAVAILABLE;
        // The resolver stage is degraded ONLY on the availability channel. A model-chosen escalate=true
        // on a healthy call is RESOLVED and stays fully graded.
        boolean resolverDegraded = resolution.status() == ResolutionStatus.ESCALATED_TO_HUMAN;

        // --- classifier stage (strict exact enum match) ---
        Boolean categoryMatch = null, urgencyMatch = null, intentMatch = null;
        if (!classifierDegraded) {
            var actual = classification.classification();
            categoryMatch = expected.category() == actual.category();
            urgencyMatch = expected.urgency() == actual.urgency();
            intentMatch = expected.intent() == actual.intent();
        }

        // --- resolver stage ---
        Boolean escalateMatch = null;
        TicketScore.SourcesResult sources = TicketScore.SourcesResult.notGraded();
        List<TicketScore.RuleViolation> mustContainMisses = List.of();
        List<TicketScore.RuleViolation> mustNotViolations = List.of();
        if (!resolverDegraded) {
            escalateMatch = expected.escalate() == resolution.escalate();
            sources = gradeSources(expected.expectedSources(), resolution.sourcesUsed());
            String reply = resolution.answer();
            mustContainMisses = missingRequired(expected.mustContain(), reply);
            mustNotViolations = forbiddenPresent(expected.mustNotContain(), reply);
        }

        boolean classifierOk = classifierDegraded
                || (Boolean.TRUE.equals(categoryMatch)
                    && Boolean.TRUE.equals(urgencyMatch)
                    && Boolean.TRUE.equals(intentMatch));
        boolean resolverOk = resolverDegraded
                || (Boolean.TRUE.equals(escalateMatch)
                    && !sources.isFailure()
                    && mustContainMisses.isEmpty()
                    && mustNotViolations.isEmpty());
        boolean passed = classifierOk && resolverOk;

        return new TicketScore(
                ticket.id(), ticket.slice(),
                classifierDegraded, resolverDegraded,
                categoryMatch, urgencyMatch, intentMatch,
                true, escalateMatch, sources, mustContainMisses, mustNotViolations,
                passed);
    }

    /**
     * Three-valued sources grading (R1):
     * <ul>
     *   <li>label {@code null} → NOT_GRADED;</li>
     *   <li>label {@code []} → STRICT: PASS iff actual is also empty, else FAIL and every actual
     *       citation is reported as an unexpected extra (an empty label must never pass vacuously);</li>
     *   <li>label non-empty → {@code expected ⊆ actual}: FAIL if any expected id is missing; otherwise
     *       PASS, with any actual ids beyond the expected set carried as WARN-only extras.</li>
     * </ul>
     */
    private TicketScore.SourcesResult gradeSources(List<String> expectedSources, List<String> actualSources) {
        if (expectedSources == null) {
            return TicketScore.SourcesResult.notGraded();
        }
        List<String> actual = actualSources == null ? List.of() : actualSources;

        if (expectedSources.isEmpty()) {
            // STRICT empty. Any citation at all is a failure; list them so the report shows what leaked.
            var grade = actual.isEmpty()
                    ? TicketScore.SourcesResult.Grade.PASS
                    : TicketScore.SourcesResult.Grade.FAIL;
            return new TicketScore.SourcesResult(grade, List.of(), List.copyOf(actual));
        }

        List<String> missing = new ArrayList<>();
        for (String want : expectedSources) {
            if (!actual.contains(want)) missing.add(want);
        }
        List<String> extra = new ArrayList<>();
        for (String got : actual) {
            if (!expectedSources.contains(got)) extra.add(got);
        }
        var grade = missing.isEmpty()
                ? TicketScore.SourcesResult.Grade.PASS   // extras are warnings, not failures
                : TicketScore.SourcesResult.Grade.FAIL;
        return new TicketScore.SourcesResult(grade, missing, extra);
    }

    private List<TicketScore.RuleViolation> missingRequired(List<String> mustContain, String reply) {
        if (mustContain == null || mustContain.isEmpty()) return List.of();
        String haystack = reply.toLowerCase();
        List<TicketScore.RuleViolation> misses = new ArrayList<>();
        for (String required : mustContain) {
            if (!haystack.contains(required.toLowerCase())) {
                // Absent by definition — no surrounding context to show.
                misses.add(new TicketScore.RuleViolation(required, ""));
            }
        }
        return misses;
    }

    private List<TicketScore.RuleViolation> forbiddenPresent(List<String> mustNotContain, String reply) {
        if (mustNotContain == null || mustNotContain.isEmpty()) return List.of();
        String haystack = reply.toLowerCase();
        List<TicketScore.RuleViolation> violations = new ArrayList<>();
        for (String forbidden : mustNotContain) {
            int at = haystack.indexOf(forbidden.toLowerCase());
            if (at >= 0) {
                violations.add(new TicketScore.RuleViolation(forbidden, contextAround(reply, at, forbidden.length())));
            }
        }
        return violations;
    }

    // ~60 chars of the ORIGINAL-case reply around the match, with ellipses where it was clipped, so a
    // reviewer sees the forbidden phrase where it actually landed.
    private String contextAround(String reply, int at, int fragmentLength) {
        int start = Math.max(0, at - CONTEXT_RADIUS);
        int end = Math.min(reply.length(), at + fragmentLength + CONTEXT_RADIUS);
        String slice = reply.substring(start, end);
        String prefix = start > 0 ? "…" : "";
        String suffix = end < reply.length() ? "…" : "";
        return prefix + slice + suffix;
    }
}
