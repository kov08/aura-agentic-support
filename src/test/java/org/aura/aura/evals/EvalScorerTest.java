package org.aura.aura.evals;

import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.ReviewReason;
import org.aura.aura.classification.TicketCategory;
import org.aura.aura.classification.TicketClassification;
import org.aura.aura.classification.TicketIntent;
import org.aura.aura.classification.TicketUrgency;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolutionStatus;
import org.aura.aura.retrieval.SourceRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

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
        // expectedCitations is null here: every ticket built by this helper sits on the "clean" slice,
        // which carries no grounding class, so the Day 16 dimension does not grade it. The grounding
        // rules get their own builders below.
        return new ExpectedResult(cat, urg, intent, escalate, sources, null, mustContain, mustNot);
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

    /**
     * Day 14: the tests below still speak in citation LABELS ("kb-returns"), because the scoring rules
     * they pin — subset semantics, the strict empty-label case, extras as warnings — are about set
     * arithmetic and have nothing to do with what a citation is called. Only this fixture changed: a
     * label becomes the breadcrumb of a {@link SourceRef}, which is the identifier
     * {@link EvalScorer#providedBreadcrumbs} grades against.
     *
     * <p>The uuid and distance are filler here for the same reason: the scorer never reads them.
     */
    private static Resolution resolved(String reply, List<String> sources, boolean escalate) {
        // Day 16: the same refs go in BOTH lists. The scorer's sources dimension grades
        // citedBreadcrumbs, and on a real RESOLVED answer every cited chunk is by construction one
        // that was provided (G4 rejects anything else) — so a fixture where they disagreed would be
        // a shape the pipeline cannot produce.
        List<SourceRef> refs = sources.stream()
                .map(s -> new SourceRef(UUID.randomUUID(), s, 0.2)).toList();
        return Resolution.resolved(reply, refs, refs, escalate);
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
        // NOT_GRADED, not PASS: the sources dimension is quarantined (Day 14). Everything else on this
        // ticket still grades, which is the property the quarantine had to preserve.
        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.NOT_GRADED);
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

    // ---- expectedSources: the QUARANTINE (Day 14) ------------------------------------------------

    @Test
    void sources_isQuarantinedRegardlessOfLabel_andCannotFailATicket() {
        // The label here is one the retired ids would have FAILED under the old grading — a non-empty
        // expectation against an actual set that shares nothing with it. Quarantined, it produces
        // NOT_GRADED and leaves the ticket passing on its other dimensions.
        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of("kb-returns"), List.of(), List.of())),
                goodClassification(),
                resolved("...", List.of("Refund Policy > Standard Refund Window"), false));

        assertThat(s.sourcesResult().grade()).isEqualTo(TicketScore.SourcesResult.Grade.NOT_GRADED);
        assertThat(s.passed())
                .as("a switched-off dimension must not fail tickets — that is the point of switching "
                        + "it off rather than leaving it permanently red")
                .isTrue();
    }

    @Test
    void sources_quarantineCarriesItsReasonSoAResultsFileExplainsItself() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(List.of("kb-returns"), List.of(), List.of())),
                goodClassification(),
                resolved("...", List.of(), false));

        // isQuarantined() is what separates "this ticket had no label" from "this dimension is off".
        // Both are NOT_GRADED, and only one of them is a decision someone made.
        assertThat(s.sourcesResult().isQuarantined()).isTrue();
        assertThat(s.sourcesResult().reason())
                .contains("QUARANTINED")
                .contains("kb-returns")        // names the retired ids
                .contains("Day 16");           // names the relabel that lifts it
    }

    // ---- expectedSources: the three-valued rule (R1), still pinned while quarantined -------------
    //
    // These call gradeSources DIRECTLY rather than through score(). That is the whole reason the
    // quarantine was implemented at the call site instead of by gutting the method: the rules below
    // are still the rules, they are still the ones Day 16 will re-enable, and a quarantine that also
    // deleted their coverage would mean re-deriving them from scratch — at which point "re-enable" is
    // a rewrite rather than a one-line change.

    @Test
    void sources_nullLabel_isNotGraded() {
        var result = EvalScorer.gradeSources(null, List.of("kb-returns", "kb-shipping"));

        assertThat(result.grade()).isEqualTo(TicketScore.SourcesResult.Grade.NOT_GRADED);
        // An unlabelled ticket is NOT quarantined — nobody switched anything off, the label is absent.
        assertThat(result.isQuarantined()).isFalse();
    }

    @Test
    void sources_emptyLabel_passesOnlyWhenActualAlsoEmpty() {
        var result = EvalScorer.gradeSources(List.of(), List.of());

        assertThat(result.grade()).isEqualTo(TicketScore.SourcesResult.Grade.PASS);
    }

    // THE TRAP (R1): an empty label with any actual citation must FAIL, never pass vacuously.
    @Test
    void sources_emptyLabel_failsWhenActualNonEmpty() {
        var result = EvalScorer.gradeSources(List.of(), List.of("kb-returns"));

        assertThat(result.grade()).isEqualTo(TicketScore.SourcesResult.Grade.FAIL);
        assertThat(result.extra()).containsExactly("kb-returns");
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void sources_nonEmptyLabel_passesOnExactSubset() {
        var result = EvalScorer.gradeSources(List.of("kb-returns"), List.of("kb-returns"));

        assertThat(result.grade()).isEqualTo(TicketScore.SourcesResult.Grade.PASS);
    }

    @Test
    void sources_nonEmptyLabel_missingExpected_fails() {
        var result = EvalScorer.gradeSources(
                List.of("kb-returns", "kb-refund-time"), List.of("kb-returns"));

        assertThat(result.grade()).isEqualTo(TicketScore.SourcesResult.Grade.FAIL);
        assertThat(result.missing()).containsExactly("kb-refund-time");
    }

    // Extra citations beyond the expected subset are WARNINGS, not failures.
    @Test
    void sources_nonEmptyLabel_extraCitations_passWithWarning() {
        var result = EvalScorer.gradeSources(
                List.of("kb-returns"), List.of("kb-returns", "kb-shipping"));

        assertThat(result.grade()).isEqualTo(TicketScore.SourcesResult.Grade.PASS);
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.extra()).containsExactly("kb-shipping");
        assertThat(result.isFailure()).isFalse();
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
        Resolution degraded = Resolution.escalatedToHuman();

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
        Resolution rDeg = Resolution.escalatedToHuman();

        TicketScore s = scorer.score(ticket(baselineLabel(null, List.of(), List.of())), cDeg, rDeg);

        assertThat(s.fullyDegraded()).isTrue();
    }

    // ---- Day 16: the grounding dimension ---------------------------------------------------------
    //
    // One test per rule, same as everything above. The dimension answers three different questions
    // depending on the ticket's class, so the tests are grouped by class rather than by outcome.

    private static final String GIFT_CARDS = "Refund Policy > Standard Refund Window > Gift Cards";
    private static final String OTHER_CHUNK = "Refund Policy > Refund Processing Times";

    private static EvalTicket groundingTicket(String slice, List<String> mustContain,
                                              List<String> mustNot, List<String> expectedCitations) {
        return new EvalTicket("g-01", slice, "some message", new ExpectedResult(
                TicketCategory.RETURNS_AND_REFUNDS, TicketUrgency.LOW, TicketIntent.GET_INFORMATION,
                !slice.equals("unanswerable") ? false : true,
                null, expectedCitations, mustContain, mustNot));
    }

    /** An answered resolution whose provided and cited lists can differ — which G4 forbids, so these
     *  fixtures are how the tripwire branches get exercised at all. */
    private static Resolution answered(String reply, List<String> provided, List<String> cited) {
        return Resolution.resolved(reply, refs(provided), refs(cited), false);
    }

    private static List<SourceRef> refs(List<String> breadcrumbs) {
        return breadcrumbs.stream().map(b -> new SourceRef(UUID.randomUUID(), b, 0.2)).toList();
    }

    @Test
    void answerable_answeredWithTheFactAndACitation_isGrounded() {
        TicketScore s = scorer.score(
                groundingTicket("answerable", List.of("15%"), List.of(), null),
                goodClassification(),
                answered("Bulk orders carry a 15% restocking fee.", List.of(OTHER_CHUNK), List.of(OTHER_CHUNK)));

        assertThat(s.grounding().outcome()).isEqualTo(TicketScore.GroundingResult.Outcome.GROUNDED);
        assertThat(s.grounding().isFailure()).isFalse();
    }

    // THE COST OF THE GATES, and the reason over-refusal is a headline number rather than a footnote.
    // The corpus had the answer; the system handed the ticket to a person anyway.
    @Test
    void answerable_refused_isOverRefusal() {
        TicketScore s = scorer.score(
                groundingTicket("answerable", List.of("15%"), List.of(), null),
                goodClassification(),
                Resolution.escalatedUngrounded(org.aura.aura.resolver.EscalationCause.UNGROUNDED));

        assertThat(s.grounding().outcome()).isEqualTo(TicketScore.GroundingResult.Outcome.OVER_REFUSED);
        assertThat(s.passed()).isFalse();
    }

    // A grounding refusal is NOT a degraded stage. Getting this wrong would have been invisible and
    // total: every correct refusal would be excluded from scoring, and the unanswerable slice would
    // report 0/0 — which reads exactly like a slice that passed.
    @Test
    void aGroundingRefusalIsScored_notTreatedAsADegradedResolverStage() {
        TicketScore s = scorer.score(
                groundingTicket("unanswerable", List.of(), List.of(), null),
                goodClassification(),
                Resolution.escalatedUngrounded(org.aura.aura.resolver.EscalationCause.UNGROUNDED));

        assertThat(s.resolverDegraded()).isFalse();
        assertThat(s.grounding().outcome())
                .isEqualTo(TicketScore.GroundingResult.Outcome.CORRECTLY_REFUSED);
        assertThat(s.grounding().isFailure()).isFalse();
    }

    // ...whereas a DEPENDENCY failure still is, and grades nothing.
    @Test
    void aDependencyEscalationDegradesTheStageAndGradesNoGrounding() {
        TicketScore s = scorer.score(
                groundingTicket("unanswerable", List.of(), List.of(), null),
                goodClassification(),
                Resolution.escalatedToHuman());

        assertThat(s.resolverDegraded()).isTrue();
        assertThat(s.grounding().isGraded()).isFalse();
    }

    // No string rule is consulted here, and none should be: the class already establishes that the
    // corpus cannot answer this ticket, so any answer at all came from training data.
    @Test
    void unanswerable_answered_isAHallucinationWithoutNeedingAStringRule() {
        TicketScore s = scorer.score(
                groundingTicket("unanswerable", List.of(), List.of(), null),
                goodClassification(),
                answered("We offer a 10% student discount.", List.of(OTHER_CHUNK), List.of(OTHER_CHUNK)));

        assertThat(s.grounding().outcome()).isEqualTo(TicketScore.GroundingResult.Outcome.HALLUCINATED);
    }

    // THE TRAP'S WHOLE POINT: fluent, plausible, well-formed, and sourced from the model's prior
    // rather than from the excerpt sitting in the prompt.
    @Test
    void trap_answeredWithTheGenericPriorValue_isAHallucination() {
        TicketScore s = scorer.score(
                groundingTicket("trap", List.of("7"), List.of("non-refundable"), List.of(GIFT_CARDS)),
                goodClassification(),
                answered("Gift cards are non-refundable once purchased.",
                        List.of(GIFT_CARDS), List.of(GIFT_CARDS)));

        assertThat(s.grounding().outcome()).isEqualTo(TicketScore.GroundingResult.Outcome.HALLUCINATED);
        assertThat(s.grounding().reason()).contains("non-refundable");
    }

    @Test
    void trap_answeredWithTheCorpusValueAndTheRightCitation_isGrounded() {
        TicketScore s = scorer.score(
                groundingTicket("trap", List.of("7"), List.of("non-refundable"), List.of(GIFT_CARDS)),
                goodClassification(),
                answered("Gift cards can be refunded within 7 days of purchase.",
                        List.of(GIFT_CARDS, OTHER_CHUNK), List.of(GIFT_CARDS)));

        assertThat(s.grounding().outcome()).isEqualTo(TicketScore.GroundingResult.Outcome.GROUNDED);
    }

    // Right answer, wrong receipt. Deliberately NOT a hallucination — the value IS the corpus's — but
    // a citation that points somewhere the fact does not live sends a reviewer to the wrong page.
    @Test
    void trap_rightValueButCitingTheWrongChunk_isAnsweredWithoutTheFact() {
        TicketScore s = scorer.score(
                groundingTicket("trap", List.of("7"), List.of("non-refundable"), List.of(GIFT_CARDS)),
                goodClassification(),
                answered("Gift cards can be refunded within 7 days.",
                        List.of(GIFT_CARDS, OTHER_CHUNK), List.of(OTHER_CHUNK)));

        assertThat(s.grounding().outcome())
                .isEqualTo(TicketScore.GroundingResult.Outcome.ANSWERED_WITHOUT_THE_FACT);
        assertThat(s.grounding().reason()).contains(GIFT_CARDS);
    }

    // Distinguished from HALLUCINATED on purpose: this usually means retrieval never supplied the
    // right chunk, which is a different bug with a different fix from "the gates let something out".
    @Test
    void answerable_answeredWithoutTheLabelledFact_isItsOwnOutcome() {
        TicketScore s = scorer.score(
                groundingTicket("answerable", List.of("36"), List.of(), null),
                goodClassification(),
                answered("Power tools are covered by our standard warranty.",
                        List.of(OTHER_CHUNK), List.of(OTHER_CHUNK)));

        assertThat(s.grounding().outcome())
                .isEqualTo(TicketScore.GroundingResult.Outcome.ANSWERED_WITHOUT_THE_FACT);
    }

    // G4 TRIPWIRE. This shape cannot occur while the gate works — which is exactly why the eval
    // watches for it. A gate nothing observes from outside is a gate a refactor can delete.
    @Test
    void answeredWithTheFactButCitingNothing_isFlaggedAsABrokenGate() {
        TicketScore s = scorer.score(
                groundingTicket("answerable", List.of("15%"), List.of(), null),
                goodClassification(),
                answered("Bulk orders carry a 15% restocking fee.", List.of(OTHER_CHUNK), List.of()));

        assertThat(s.grounding().outcome()).isEqualTo(TicketScore.GroundingResult.Outcome.HALLUCINATED);
        assertThat(s.grounding().reason()).contains("cited NOTHING");
    }

    @Test
    void citingAChunkThatWasNeverRetrieved_isFlaggedAsABrokenGate() {
        TicketScore s = scorer.score(
                groundingTicket("answerable", List.of("15%"), List.of(), null),
                goodClassification(),
                answered("Bulk orders carry a 15% restocking fee.",
                        List.of(OTHER_CHUNK), List.of("Warranty Policy > Remedies")));

        assertThat(s.grounding().outcome()).isEqualTo(TicketScore.GroundingResult.Outcome.HALLUCINATED);
        assertThat(s.grounding().reason()).contains("not retrieved");
    }

    /**
     * THE BEFORE-ARM RULE, and the one place a wrong default would have produced a flattering lie.
     *
     * <p>Under {@code CitationRegime.ABSENT} the same uncited answer that is a broken-gate finding
     * above is simply GROUNDED, because the pipeline being replayed had no citations to give. Without
     * this, the Day 16 before/after report would score every correct pre-grounding answer as a
     * hallucination and the after arm would win against a baseline penalised for lacking a feature it
     * never had.
     */
    @Test
    void underTheAbsentCitationRegime_anUncitedButCorrectAnswerIsGrounded() {
        EvalTicket ticket = groundingTicket("trap", List.of("7"), List.of("non-refundable"),
                List.of(GIFT_CARDS));
        Resolution uncited = answered("Gift cards can be refunded within 7 days.",
                List.of(GIFT_CARDS), List.of());

        assertThat(scorer.score(ticket, goodClassification(), uncited,
                        EvalScorer.CitationRegime.ABSENT).grounding().outcome())
                .isEqualTo(TicketScore.GroundingResult.Outcome.GROUNDED);
        // ...and the SAME response under today's pipeline is still a finding. One input, two regimes,
        // two correct answers — which is why the regime is a parameter rather than a heuristic.
        assertThat(scorer.score(ticket, goodClassification(), uncited,
                        EvalScorer.CitationRegime.ENFORCED).grounding().outcome())
                .isEqualTo(TicketScore.GroundingResult.Outcome.HALLUCINATED);
    }

    // The checks that DON'T depend on citations still apply to the before arm — otherwise the regime
    // would be an amnesty rather than a scope.
    @Test
    void theAbsentRegimeStillCatchesPriorLeaksAndMissingFacts() {
        EvalTicket trap = groundingTicket("trap", List.of("7"), List.of("non-refundable"),
                List.of(GIFT_CARDS));

        assertThat(scorer.score(trap, goodClassification(),
                        answered("Gift cards are non-refundable.", List.of(GIFT_CARDS), List.of()),
                        EvalScorer.CitationRegime.ABSENT).grounding().outcome())
                .isEqualTo(TicketScore.GroundingResult.Outcome.HALLUCINATED);

        assertThat(scorer.score(
                        groundingTicket("unanswerable", List.of(), List.of(), null),
                        goodClassification(),
                        answered("We offer 10% off for students.", List.of(OTHER_CHUNK), List.of()),
                        EvalScorer.CitationRegime.ABSENT).grounding().outcome())
                .isEqualTo(TicketScore.GroundingResult.Outcome.HALLUCINATED);
    }

    // The 24 pre-Day-16 tickets carry no claim about what the corpus contains, so grading them here
    // would be inventing a label rather than reading one.
    @Test
    void aPreGroundingSliceIsNotGradedByTheGroundingDimension() {
        TicketScore s = scorer.score(
                ticket(baselineLabel(null, List.of(), List.of())),
                goodClassification(),
                resolved("anything at all", List.of("Refund Policy"), false));

        assertThat(s.grounding().isGraded()).isFalse();
        assertThat(s.grounding().groundingClass()).isNull();
        assertThat(s.grounding().isFailure()).isFalse();   // and it must not drag the ticket to a fail
    }
}
