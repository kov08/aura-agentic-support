package org.aura.aura.evals;

import org.aura.aura.classification.TicketCategory;
import org.aura.aura.classification.TicketIntent;
import org.aura.aura.classification.TicketUrgency;

import java.util.List;

/**
 * The hand-reviewed expected answer for one golden ticket — the label.
 *
 * <p>category/urgency/intent are the REAL production enums, not strings, on purpose: an illegal
 * value in the JSON throws at deserialization, so a renamed or dropped enum constant breaks
 * {@link GoldenSetIntegrityTest} loudly instead of silently invalidating a label (the label-rot
 * tripwire the brief asked for).
 *
 * <p>{@code expectedSources} is three-valued, and the three cases mean different things — see
 * {@link EvalScorer} for the grading:
 * <ul>
 *   <li>{@code null} — retrieval is UNGRADED for this ticket (the KB mapping is ambiguous);</li>
 *   <li>{@code []} — STRICT: the resolver must cite nothing. A vacuous subset "pass" is a bug;
 *       an empty label with any actual citation FAILS;</li>
 *   <li>non-empty — {@code expected ⊆ actual}; extra citations are WARNINGS, not failures.</li>
 * </ul>
 *
 * <p>The must/mustNot lists are populated only on the high-stakes third of the set; elsewhere they
 * are empty. Every entry is a substring matched case-insensitively against the reply text.
 */
public record ExpectedResult(
        TicketCategory category,
        TicketUrgency urgency,
        TicketIntent intent,
        boolean escalate,
        List<String> expectedSources,
        /**
         * Day 16: breadcrumbs the answer MUST have cited, or null for "do not grade which chunk".
         *
         * <h2>Why this is not just a relabelled {@code expectedSources}</h2>
         * They are in different vocabularies and grade different things, and merging them would put
         * two meanings in one slot. {@code expectedSources} above is the Day 13 dimension, still
         * quarantined, whose entries are retired hardcoded-KB ids ({@code kb-returns}); it graded
         * what retrieval SUPPLIED. This grades what the answer CITED, in breadcrumbs, which only
         * became a checkable fact when G4 started verifying citations. Reusing the old field would
         * have left a golden set where an entry's meaning depended on which ticket it was attached
         * to — and the quarantine note already warns that relabelling that field needs a live run
         * against the real corpus, which grounding work never required.
         *
         * <h2>Populated only where the chunk is unambiguous</h2>
         * Null on {@code answerable} tickets and non-null on {@code trap} ones, following what each
         * class can honestly assert. A trap has exactly one chunk that contains the counter-prior
         * value, so naming it is a fact. Several answerable facts appear in two chunks (35 USD is in
         * both the delivery table and the free-shipping section), so pinning one would fail a correct
         * answer that cited the other — a label testing retrieval's tie-break while claiming to test
         * grounding.
         */
        List<String> expectedCitations,
        List<String> mustContain,
        List<String> mustNotContain
) {}
