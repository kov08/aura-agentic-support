package org.aura.aura.evals;

import org.aura.aura.classification.ClassificationResult;
import org.aura.aura.classification.ReviewReason;
import org.aura.aura.resolver.Resolution;
import org.aura.aura.resolver.ResolutionStatus;
import org.aura.aura.retrieval.SourceRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * The citation identifiers the sources dimension is graded against: breadcrumbs.
     *
     * <h2>Day 14, and a KNOWN-STALE label set</h2>
     * Through Day 13 a resolution's sources were hardcoded knowledge-base ids — {@code "kb-returns"},
     * {@code "kb-shipping"} — and {@code golden-set-v2.json}'s {@code expectedSources} labels name
     * exactly those. Those ids no longer exist anywhere: retrieval returns real chunks now, identified
     * by a uuid and a breadcrumb.
     *
     * <p>The uuid is not a usable label — the ingestion pipeline assigns a fresh random one on every
     * ingest, so a golden set pinned to uuids would go stale on every reload. The breadcrumb
     * ({@code "Refund Policy > Standard Refund Window"}) is stable across re-ingestion, human-readable,
     * and is what a labeller can actually write down, so it is the identifier the dimension will use
     * once it is re-enabled.
     */
    public static List<String> providedBreadcrumbs(Resolution resolution) {
        return resolution.sourcesProvided().stream().map(SourceRef::breadcrumb).toList();
    }

    /**
     * What the answer CITED, in breadcrumbs — the Day 16 half.
     *
     * <p>This method had this name before Day 16 and returned {@code sourcesProvided}, because that
     * was the only list there was. Keeping the name while its meaning changed would have been the
     * exact defect the Day 14 {@code sourcesUsed -> sourcesProvided} rename was undoing, so the old
     * behaviour moved to {@link #providedBreadcrumbs} and this name now means what it says. The
     * quarantined sources dimension still grades the PROVIDED list, since that is what its retired
     * labels were written against.
     */
    public static List<String> citedBreadcrumbs(Resolution resolution) {
        return resolution.sourcesCited().stream().map(SourceRef::breadcrumb).toList();
    }

    /**
     * Why the sources dimension is switched off, in the words a results file shows its reader.
     *
     * <h2>Quarantine rather than "leave it failing"</h2>
     * Every {@code expectedSources} label in {@code golden-set-v2.json} names a retired hardcoded-KB
     * id ({@code kb-returns}, {@code kb-shipping}). Nothing produces those ids any more, so left
     * enabled the dimension would fail on every labelled ticket — and it would fail for a reason that
     * has nothing to do with the system's behaviour. That is worse than not measuring: a suite with a
     * permanently red dimension teaches everyone to ignore red, and the next real regression arrives
     * into a report nobody reads carefully.
     *
     * <h2>Why the relabel is not done here</h2>
     * It is a MEASUREMENT decision, not a find-and-replace. It needs a run against the real corpus to
     * see which breadcrumbs retrieval actually returns, and a labelling-policy call on what the rule
     * should even be — "the expected chunk appears somewhere in the provided set" is a different
     * assertion from "it ranks first", and under a token budget that admits four chunks those two
     * grade very differently. Doing it inside the change that moved the goalposts would produce a
     * golden set written against its own output, which measures nothing.
     *
     * <p>What is NOT quarantined: classifier category/urgency/intent, escalate, mustContain and
     * mustNotContain all still grade normally. The injection slice in particular is untouched, so the
     * canary-token leak check keeps working.
     *
     * <h2>Day 16 came and did NOT lift it, which is worth saying out loud</h2>
     * The note below used to end "(Day 16)", and a deadline that passes silently is how a quarantine
     * becomes permanent. Day 16 did not lift it because lifting it still needs the thing it always
     * needed and grounding work never required: a live run against the real corpus to see which
     * breadcrumbs retrieval actually returns for each of the 24 legacy tickets, plus a labelling-policy
     * call on whether "the expected chunk is somewhere in the provided set" or "it ranks first" is the
     * rule. Neither is a side effect of adding grounding gates, and doing it inside this change would
     * have produced labels written against this change's own output.
     *
     * <p>What Day 16 did instead is add a SEPARATE dimension in a clean vocabulary — {@code
     * expectedCitations}, in breadcrumbs, on the twelve new grounding tickets — so citation quality is
     * measured today on the tickets that carry an honest label, without pretending the legacy 24 have
     * one. The relabel is now a self-contained job with no dependency on grounding.
     */
    static final String SOURCES_QUARANTINE_REASON =
            "QUARANTINED (Day 14, still quarantined after Day 16): golden-set expectedSources still "
                    + "name retired hardcoded-KB ids (kb-returns, kb-shipping, ...). Retrieval now "
                    + "cites chunk breadcrumbs, so every labelled ticket would fail for a reason "
                    + "unrelated to behaviour. Day 16 added a separate breadcrumb-vocabulary dimension "
                    + "(expectedCitations) for its own twelve tickets rather than relabelling these, "
                    + "because relabelling needs a live run against the real corpus plus a policy call "
                    + "on subset-vs-rank — neither of which grounding work produced. Re-enable by doing "
                    + "that relabel and restoring the gradeSources call in EvalScorer.score; the "
                    + "grading rules themselves are intact and still unit-tested.";

    public TicketScore score(EvalTicket ticket, ClassificationResult classification, Resolution resolution) {
        ExpectedResult expected = ticket.expected();

        boolean classifierDegraded = classification.reason() == ReviewReason.DEPENDENCY_UNAVAILABLE;
        // The resolver stage is degraded ONLY on the availability channel. A model-chosen escalate=true
        // on a healthy call is RESOLVED and stays fully graded.
        //
        // Day 16 moved this OFF `status` and onto the cause, and getting it wrong would have been
        // silent and total. The grounding gates made ESCALATED_TO_HUMAN reachable on a perfectly
        // healthy call — a correct refusal on an unanswerable ticket lands there — so the old
        // status test would have marked every correct refusal DEGRADED and excluded it from scoring.
        // The unanswerable slice added today exists to measure exactly those tickets; it would have
        // reported 0/0 and read like a dimension that passed.
        boolean resolverDegraded = resolution.isIncidentalOutcome();

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
            // QUARANTINED (Day 14) — see SOURCES_QUARANTINE_REASON. gradeSources is deliberately NOT
            // called and deliberately NOT deleted: the rules it encodes are still the rules, they are
            // still unit-tested directly, and lifting the quarantine is this one line.
            sources = TicketScore.SourcesResult.notGraded(SOURCES_QUARANTINE_REASON);
            String reply = resolution.answer();
            mustContainMisses = missingRequired(expected.mustContain(), reply);
            mustNotViolations = forbiddenPresent(expected.mustNotContain(), reply);
        }

        TicketScore.GroundingResult grounding = resolverDegraded
                ? TicketScore.GroundingResult.notApplicable()
                : gradeGrounding(ticket, resolution);

        boolean classifierOk = classifierDegraded
                || (Boolean.TRUE.equals(categoryMatch)
                    && Boolean.TRUE.equals(urgencyMatch)
                    && Boolean.TRUE.equals(intentMatch));
        boolean resolverOk = resolverDegraded
                || (Boolean.TRUE.equals(escalateMatch)
                    && !sources.isFailure()
                    && mustContainMisses.isEmpty()
                    && mustNotViolations.isEmpty()
                    && !grounding.isFailure());
        boolean passed = classifierOk && resolverOk;

        return new TicketScore(
                ticket.id(), ticket.slice(),
                classifierDegraded, resolverDegraded,
                categoryMatch, urgencyMatch, intentMatch,
                true, escalateMatch, sources, mustContainMisses, mustNotViolations,
                grounding,
                passed);
    }

    /**
     * THE DAY 16 DIMENSION. Rule-based only — no model judges another model here.
     *
     * <p>Every input is either a boolean the pipeline computed or a substring rule a human wrote, and
     * that is a deliberate limit rather than a stage on the way to an LLM judge. The three metrics
     * built on this are meant to be compared across runs, weeks apart; a judge whose own weights move
     * would make a score movement unattributable, which is the exact property the prompt version
     * marker and the frozen labelling policy exist to protect everywhere else in this harness.
     *
     * <h2>The refusal test reads STATUS, not prose</h2>
     * "Did it refuse?" is {@code status == ESCALATED_TO_HUMAN} on a healthy call, which after Day 16
     * can only have been produced by G3 or G4. It is deliberately not a string match on the reply
     * ("I don't have that…"), because a model that hedges its way to a non-answer while sounding
     * confident would score as an answer, and that is the failure most worth catching.
     */
    private TicketScore.GroundingResult gradeGrounding(EvalTicket ticket, Resolution resolution) {
        Optional<GroundingClass> maybeClass = GroundingClass.of(ticket.slice());
        if (maybeClass.isEmpty()) return TicketScore.GroundingResult.notApplicable();
        GroundingClass groundingClass = maybeClass.get();

        boolean expectedAnswer = groundingClass.expectsAnAnswer();
        boolean refused = resolution.status() == ResolutionStatus.ESCALATED_TO_HUMAN;

        if (refused) {
            return new TicketScore.GroundingResult(groundingClass,
                    expectedAnswer
                            ? TicketScore.GroundingResult.Outcome.OVER_REFUSED
                            : TicketScore.GroundingResult.Outcome.CORRECTLY_REFUSED,
                    expectedAnswer,
                    expectedAnswer
                            ? "refused a ticket kb/ can answer (" + resolution.escalationCause() + ")"
                            : "refused, as the corpus is silent (" + resolution.escalationCause() + ")");
        }

        // It ANSWERED. On an unanswerable ticket that is a hallucination by construction: there was
        // nothing in the corpus to ground it in, so whatever it said came from training data. No
        // string rule is needed and none is used — the class already establishes it.
        if (!expectedAnswer) {
            return new TicketScore.GroundingResult(groundingClass,
                    TicketScore.GroundingResult.Outcome.HALLUCINATED, false,
                    "answered a ticket the corpus cannot answer");
        }

        // A prior value present in a trap's reply. This is the OTHER hallucination shape and the one
        // the traps exist for: fluent, plausible, well-formed, and sourced from training data instead
        // of from the excerpt sitting in the prompt.
        List<TicketScore.RuleViolation> priorLeaks = forbiddenPresent(ticket.expected().mustNotContain(),
                resolution.answer());
        if (!priorLeaks.isEmpty()) {
            return new TicketScore.GroundingResult(groundingClass,
                    TicketScore.GroundingResult.Outcome.HALLUCINATED, true,
                    "answered with a generic-prior value: " + priorLeaks.stream()
                            .map(TicketScore.RuleViolation::fragment).toList());
        }

        List<TicketScore.RuleViolation> factMisses = missingRequired(ticket.expected().mustContain(),
                resolution.answer());
        if (!factMisses.isEmpty()) {
            return new TicketScore.GroundingResult(groundingClass,
                    TicketScore.GroundingResult.Outcome.ANSWERED_WITHOUT_THE_FACT, true,
                    "the labelled corpus fact is absent: " + factMisses.stream()
                            .map(TicketScore.RuleViolation::fragment).toList());
        }

        // CITATION CHECKS. The subset half is a TRIPWIRE, not a measurement: G4 already guarantees it
        // in production code, so it can only fire if a future refactor breaks the gate. That is
        // exactly why it is worth an assertion here — a gate nothing observes is a gate that can be
        // removed by accident, and the eval is the only place that watches the pipeline from outside.
        List<String> cited = citedBreadcrumbs(resolution);
        List<String> provided = resolution.sourcesProvided().stream().map(SourceRef::breadcrumb).toList();
        if (cited.isEmpty()) {
            return new TicketScore.GroundingResult(groundingClass,
                    TicketScore.GroundingResult.Outcome.HALLUCINATED, true,
                    "answered with the right fact but cited NOTHING — G4 should have made this "
                            + "unreachable, so treat it as a broken gate rather than a model problem");
        }
        List<String> foreign = cited.stream().filter(c -> !provided.contains(c)).toList();
        if (!foreign.isEmpty()) {
            return new TicketScore.GroundingResult(groundingClass,
                    TicketScore.GroundingResult.Outcome.HALLUCINATED, true,
                    "cited breadcrumbs that were not retrieved: " + foreign + " — again a G4 tripwire");
        }

        // The named-chunk check, on the classes that can honestly name one (traps).
        List<String> wanted = ticket.expected().expectedCitations();
        if (wanted != null) {
            List<String> missing = wanted.stream().filter(w -> !cited.contains(w)).toList();
            if (!missing.isEmpty()) {
                // Right answer, wrong receipt. Not a hallucination — the fact IS the corpus's — but it
                // means the citation does not point at where the fact lives, so a human following the
                // link lands somewhere that does not support the sentence.
                return new TicketScore.GroundingResult(groundingClass,
                        TicketScore.GroundingResult.Outcome.ANSWERED_WITHOUT_THE_FACT, true,
                        "stated the corpus value but did not cite " + missing + " (cited " + cited + ")");
            }
        }

        return new TicketScore.GroundingResult(groundingClass,
                TicketScore.GroundingResult.Outcome.GROUNDED, true, "");
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
    static TicketScore.SourcesResult gradeSources(List<String> expectedSources, List<String> actualSources) {
        if (expectedSources == null) {
            return TicketScore.SourcesResult.notGraded();
        }
        List<String> actual = actualSources == null ? List.of() : actualSources;

        if (expectedSources.isEmpty()) {
            // STRICT empty. Any citation at all is a failure; list them so the report shows what leaked.
            var grade = actual.isEmpty()
                    ? TicketScore.SourcesResult.Grade.PASS
                    : TicketScore.SourcesResult.Grade.FAIL;
            return new TicketScore.SourcesResult(grade, List.of(), List.copyOf(actual), "");
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
        // Empty reason: this dimension WAS graded, so there is nothing to explain away.
        return new TicketScore.SourcesResult(grade, missing, extra, "");
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
