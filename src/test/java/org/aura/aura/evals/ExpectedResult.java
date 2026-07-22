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
        List<String> mustContain,
        List<String> mustNotContain
) {}
