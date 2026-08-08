package org.aura.aura.evals;

/**
 * The written law that governs how a golden label is assigned — the golden set's "legislation before
 * relabelling". It is documentation with teeth: {@link GoldenSetIntegrityTest} asserts it is present and
 * complete, so a v2 set can never ship without its rulebook attached.
 *
 * <p>Why the labels reference a policy at all: v1 relabelled three tickets by taste, which is how two
 * reviewers end up disagreeing forever. v2 writes the rule first and then makes the label conform, so a
 * disputed label is settled by reading the policy, not by re-arguing the ticket. The fields:
 * <ul>
 *   <li>{@code intentUnderInjection} — under injection, {@code intent} records the SURFACE request only;
 *       the attack posture is written by {@code escalate} + the canary rules. One writer per field, so a
 *       reviewer never smuggles "this is an attack" into the intent slot.</li>
 *   <li>{@code categoryTieBreak} — a multi-topic ticket takes the category of the actionable request, and
 *       between two actions the one with financial consequence wins.</li>
 *   <li>{@code urgencyRubric} — a FROZEN, CHARACTER-IDENTICAL copy of the classifier-v2
 *       {@code <urgency_rubric>} (tags included), not a summary. A paraphrase is a re-authored law: the
 *       first draft of this field silently dropped the CRITICAL "legal/safety threat" clause, which would
 *       have left {@code out-of-scope-01}'s CRITICAL label unsupported by its own stated rule. Freezing
 *       the exact text means any future gap between the live prompt and this law is a DELIBERATE edit a
 *       reviewer can see in a diff, never an accident of re-summarising. Only the line endings are
 *       normalised to LF — EOL is not semantic content, and storing the prompt's CRLF would make a mere
 *       checkout difference read as divergence, the very accident the freeze exists to exclude.</li>
 *   <li>{@code urgencyRubricSource} — the provenance stamp for that frozen copy, kept in its OWN field so
 *       {@code urgencyRubric} stays purely verbatim and directly comparable to the prompt block.</li>
 * </ul>
 *
 * <p>{@code version} travels with {@link GoldenSet#goldenSetVersion()} — they are bumped together, so a
 * set carrying policy vN is exactly the set relabelled under law vN.
 */
public record LabelingPolicy(
        int version,
        /**
         * v3: the law for the three grounding classes.
         *
         * <p>It exists because those labels are unlike every other field here. {@code category} and
         * {@code urgency} are claims about the TICKET, and a ticket does not change. A grounding
         * class is a claim about the CORPUS — "kb/ is silent on this" — so it can be falsified by an
         * edit to a file in a different directory, with nothing in this one changing. Writing the
         * rule down is what makes that failure mode reviewable instead of a mystery score movement.
         */
        String groundingClassRule,
        String intentUnderInjection,
        String categoryTieBreak,
        String urgencyRubric,
        String urgencyRubricSource
) {}
