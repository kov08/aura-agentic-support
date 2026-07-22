package org.aura.aura.evals;

import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.ReviewReason;
import org.aura.aura.classification.TicketCategory;
import org.aura.aura.classification.TicketClassification;
import org.aura.aura.classification.TicketIntent;
import org.aura.aura.classification.TicketUrgency;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EvalScorer}. It is a pure function, so every branch is exercised here with
 * hand-built service responses — no Spring, no network. These tests are the scorer's specification:
 * each names one rule of the grading policy.
 */
class EvalScorerTest {

    private final EvalScorer scorer = new EvalScorer();

    // ---- builders -------------------------------------------------------------------------------

    private static EvalTicket ticket(ExpectedResult expected) {
        return new EvalTicket("t-01", "clean", "some message", expected);
    }

    private static ExpectedResult expected(TicketCategory cat, TicketUrgency urg, TicketIntent intent,
                                           boolean escalate, List<String> sources,
                                           List<String> mustContain, List<String> mustNot) {
        return new ExpectedResult(cat, urg, intent, escalate, sources, mustContain, mustNot);
    }

    // A label that matches the "good" responses below on every structured field.
    private static ExpectedResult baselineLabel(List<String> sources, List<String> mustContain, List<String> mustNot) {
        return expected(TicketCategory.RETURNS_AND_REFUNDS, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION,
                false, sources, mustContain, mustNot);
    }

    private static ClassificationResult classified(TicketCategory cat, TicketUrgency urg, TicketIntent intent) {
        return new ClassificationResult(
                new TicketClassification(cat, urg, intent, 0.95), false, ReviewReason.NONE);
    }

    private static ClassificationResult goodClassification() {
        return classified(TicketCategory.RETURNS_AND_REFUNDS, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION);
    }

    private static Resolution resolved(String reply, List<String> sources, boolean escalate) {
        return new Resolution(reply, sources, ResolutionStatus.RESOLVED, escalate);
    }

    // ---- happy path -----------------------------------------------------------------------------

    @Test
    void allFieldsMatch_ticketPasses() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of("kb-returns"), List.of(), List.of())),
                goodClassification(),
                resolved("Returns are accepted within 30 days.", List.of("kb-returns"), false));

        assertThat(s.passed()).isTrue();
        assertThat(s.categoryMatch()).isTrue();
        assertThat(s.urgencyMatch()).isTrue();
        assertThat(s.intentMatch()).isTrue();
        assertThat(s.escalateMatch()).isTrue();
        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.PASS);
        assertThat(s.mustContainMisses()).isEmpty();
        assertThat(s.mustNotViolations()).isEmpty();
    }

    // ---- one test per single-field mismatch -----------------------------------------------------

    @Test
    void categoryMismatch_failsThatFieldAndTheTicket() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), List.of())),
                classified(TicketCategory.BILLING, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION),
                resolved("...", List.of(), false));

        assertThat(s.categoryMatch()).isFalse();
        assertThat(s.passed()).isFalse();
    }

    @Test
    void urgencyMismatch_failsThatFieldAndTheTicket() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), List.of())),
                classified(TicketCategory.RETURNS_AND_REFUNDS, TicketUrgency.CRITICAL, TicketIntent.GET_INFORMATION),
                resolved("...", List.of(), false));

        assertThat(s.urgencyMatch()).isFalse();
        assertThat(s.passed()).isFalse();
    }

    @Test
    void intentMismatch_failsThatFieldAndTheTicket() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), List.of())),
                classified(TicketCategory.RETURNS_AND_REFUNDS, TicketUrgency.MEDIUM, TicketIntent.REQUEST_ACTION),
                resolved("...", List.of(), false));

        assertThat(s.intentMatch()).isFalse();
        assertThat(s.passed()).isFalse();
    }

    @Test
    void escalateMismatch_failsThatFieldAndTheTicket() {
        // Label says do not escalate; the model escalated.
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), List.of())),
                goodClassification(),
                resolved("I'm escalating this to a specialist.", List.of(), true));

        assertThat(s.escalateMatch()).isFalse();
        assertThat(s.passed()).isFalse();
    }

    // ---- reply-text rules -----------------------------------------------------------------------

    @Test
    void mustNotViolation_isCapturedWithSurroundingContext() {
        List<String> mustNot = List.of("refund has been processed");
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), mustNot)),
                goodClassification(),
                resolved("Good news — your refund has been processed and will arrive soon.", List.of(), false));

        assertThat(s.passed()).isFalse();
        assertThat(s.mustNotViolations()).hasSize(1);
        TicketScore.RuleViolation v = s.mustNotViolations().get(0);
        assertThat(v.fragment()).isEqualTo("refund has been processed");
        // Context shows the phrase where it landed, in the reply's original casing.
        assertThat(v.context()).contains("your refund has been processed and");
    }

    @Test
    void mustContainMiss_isReportedWhenRequiredFragmentAbsent() {
        List<String> mustContain = List.of("escalat");
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, mustContain, List.of())),
                goodClassification(),
                resolved("I've forwarded this along; someone will follow up.", List.of(), false));

        assertThat(s.passed()).isFalse();
        assertThat(s.mustContainMisses()).hasSize(1);
        assertThat(s.mustContainMisses().get(0).fragment()).isEqualTo("escalat");
    }

    @Test
    void matchingIsCaseInsensitive() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of("ESCALAT"), List.of("REFUND HAS BEEN PROCESSED"))),
                goodClassification(),
                resolved("I'm Escalating this now; no refund has been processed yet.", List.of(), false));

        // mustContain "ESCALAT" is satisfied by "Escalating"; mustNot fires on the mixed-case reply.
        assertThat(s.mustContainMisses()).isEmpty();
        assertThat(s.mustNotViolations()).hasSize(1);
    }

    // KNOWN LIMITATION, documented not fixed: substring matching cannot see negation. A reply that
    // says the refund has NOT been processed still contains the forbidden phrase "refund has been
    // processed", so it is flagged a violation. The mustNot rules are deliberately written as
    // commitment phrases that a careful reply avoids entirely, which keeps this false-positive rare —
    // but the scorer does not pretend to understand the sentence.
    @Test
    void mustNot_falsePositiveOnNegatedCommitment_isAKnownLimitation() {
        List<String> mustNot = List.of("refund has been processed");
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), mustNot)),
                goodClassification(),
                resolved("I can't yet confirm your refund has been processed — a specialist will verify.",
                        List.of(), false));

        // Flagged despite the "can't yet confirm" negation. This test pins the behaviour so a future
        // reader knows it is understood, not overlooked.
        assertThat(s.mustNotViolations()).hasSize(1);
        assertThat(s.passed()).isFalse();
    }

    @Test
    void emptyRuleLists_gradeNoReplyText() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), List.of())),
                goodClassification(),
                resolved("anything at all goes here", List.of(), false));

        assertThat(s.mustContainMisses()).isEmpty();
        assertThat(s.mustNotViolations()).isEmpty();
        assertThat(s.passed()).isTrue();
    }

    // ---- expectedSources: the three-valued rule (R1) --------------------------------------------

    @Test
    void sources_nullLabel_isNotGraded() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), List.of())),
                goodClassification(),
                resolved("...", List.of("kb-returns", "kb-shipping"), false));

        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.NOT_GRADED);
        // Ungraded sources cannot fail the ticket.
        assertThat(s.passed()).isTrue();
    }

    @Test
    void sources_emptyLabel_passesOnlyWhenActualAlsoEmpty() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of(), List.of(), List.of())),
                goodClassification(),
                resolved("...", List.of(), false));

        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.PASS);
        assertThat(s.passed()).isTrue();
    }

    // THE TRAP (R1): an empty label with any actual citation must FAIL, never pass vacuously.
    @Test
    void sources_emptyLabel_failsWhenActualNonEmpty() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of(), List.of(), List.of())),
                goodClassification(),
                resolved("...", List.of("kb-returns"), false));

        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.FAIL);
        assertThat(s.sourcesResult().extra()).containsExactly("kb-returns");
        assertThat(s.passed()).isFalse();
    }

    @Test
    void sources_nonEmptyLabel_passesOnExactSubset() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of("kb-returns"), List.of(), List.of())),
                goodClassification(),
                resolved("...", List.of("kb-returns"), false));

        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.PASS);
        assertThat(s.passed()).isTrue();
    }

    @Test
    void sources_nonEmptyLabel_missingExpected_fails() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of("kb-returns", "kb-refund-time"), List.of(), List.of())),
                goodClassification(),
                resolved("...", List.of("kb-returns"), false));

        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.FAIL);
        assertThat(s.sourcesResult().missing()).containsExactly("kb-refund-time");
        assertThat(s.passed()).isFalse();
    }

    // Extra citations beyond the expected subset are WARNINGS, not failures.
    @Test
    void sources_nonEmptyLabel_extraCitations_passWithWarning() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of("kb-returns"), List.of(), List.of())),
                goodClassification(),
                resolved("...", List.of("kb-returns", "kb-shipping"), false));

        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.PASS);
        assertThat(s.sourcesResult().hasWarnings()).isTrue();
        assertThat(s.sourcesResult().extra()).containsExactly("kb-shipping");
        assertThat(s.passed()).isTrue();
    }

    // ---- per-stage degradation ------------------------------------------------------------------

    @Test
    void classifierDependencyUnavailable_excludesClassifierFieldsButStillGradesResolver() {
        ClassificationResult degraded = new ClassificationResult(
                new TicketClassification(TicketCategory.OTHER, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION, 0.0),
                true, ReviewReason.DEPENDENCY_UNAVAILABLE);

        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of("kb-returns"), List.of(), List.of())),
                degraded,
                resolved("Returns within 30 days.", List.of("kb-returns"), false));

        assertThat(s.classifierDegraded()).isTrue();
        assertThat(s.categoryMatch()).isNull();
        assertThat(s.urgencyMatch()).isNull();
        assertThat(s.intentMatch()).isNull();
        // Resolver stage still fully graded, and it matches → the ticket passes on its graded half.
        assertThat(s.escalateMatch()).isTrue();
        assertThat(s.passed()).isTrue();
    }

    // A LOW_CONFIDENCE fallback is NOT degraded — it is a real outcome scored against the label, so
    // the neutral fallback labels (OTHER/MEDIUM/GET_INFORMATION) count as genuine mismatches.
    @Test
    void classifierLowConfidence_isScoredNotExcluded() {
        ClassificationResult lowConf = new ClassificationResult(
                new TicketClassification(TicketCategory.OTHER, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION, 0.0),
                true, ReviewReason.LOW_CONFIDENCE);

        TicketScore s = scorer.score(
                ticket(expected(TicketCategory.BILLING, TicketUrgency.HIGH, TicketIntent.REPORT_PROBLEM,
                        false, null, List.of(), List.of())),
                lowConf,
                resolved("...", List.of(), false));

        assertThat(s.classifierDegraded()).isFalse();
        assertThat(s.categoryMatch()).isFalse();   // OTHER != BILLING, counted as a real miss
        assertThat(s.passed()).isFalse();
    }

    @Test
    void resolverEscalatedFallback_excludesResolverFieldsButStillGradesClassifier() {
        Resolution degraded = new Resolution(
                "escalated to a human", List.of(), ResolutionStatus.ESCALATED_TO_HUMAN, true);

        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of("kb-returns"), List.of("escalat"), List.of())),
                goodClassification(),
                degraded);

        assertThat(s.resolverDegraded()).isTrue();
        assertThat(s.escalateMatch()).isNull();
        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.NOT_GRADED);
        assertThat(s.mustContainMisses()).isEmpty();   // reply text not graded on a degraded stage
        // Classifier stage still graded and matches.
        assertThat(s.categoryMatch()).isTrue();
        assertThat(s.passed()).isTrue();
    }

    @Test
    void bothStagesDegraded_isFullyDegraded() {
        ClassificationResult cDeg = new ClassificationResult(
                new TicketClassification(TicketCategory.OTHER, TicketUrgency.MEDIUM, TicketIntent.GET_INFORMATION, 0.0),
                true, ReviewReason.DEPENDENCY_UNAVAILABLE);
        Resolution rDeg = new Resolution(
                "escalated", List.of(), ResolutionStatus.ESCALATED_TO_HUMAN, true);

        TicketScore s = scorer.score(ticket(baselineLabel(null, List.of(), List.of())), cDeg, rDeg);

        assertThat(s.fullyDegraded()).isTrue();
    }
}
