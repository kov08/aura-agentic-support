package org.aura.aura.evals;

import java.util.Optional;

/**
 * What a Day 16 grounding ticket is FOR — the three ways a grounded system can be wrong, one class
 * each.
 *
 * <h2>Derived from the slice rather than labelled twice</h2>
 * A ticket's {@code slice} already states "the failure family this ticket probes", and for these three
 * families the family IS the grading rule. A separate {@code groundingClass} label beside a
 * {@code "trap"} slice would be the same fact written down twice, which is the shape of defect the
 * rest of this package spends its comments removing: two copies drift, and the drift is invisible
 * because both look right on their own. {@link GoldenSetIntegrityTest} already validates slices
 * against a fixed list, so the string is not free-form.
 *
 * <h2>The legacy 24 have no class, deliberately</h2>
 * {@link #of} returns empty for {@code clean}, {@code injection}, {@code noisy} and the rest, and the
 * grounding dimension does not grade them. They were written before grounding was measurable and
 * their labels make no claim about what the corpus contains — scoring them here would be inventing a
 * label rather than reading one.
 */
public enum GroundingClass {

    /**
     * The corpus contains the answer. Expect a RESOLVED answer that cites something and states the
     * fact. A refusal here is an OVER-REFUSAL: the system had what it needed and did not use it,
     * which is the specific cost of adding hard grounding gates and the reason it is measured rather
     * than assumed away.
     */
    ANSWERABLE,

    /**
     * The corpus is silent, and the ticket still reads as an ordinary support question — so the model
     * cannot earn a refusal by noticing it is off-topic. Expect ESCALATED_TO_HUMAN. An answer here is
     * a HALLUCINATION by construction: there was nothing to ground it in, so whatever it said came
     * from training data.
     */
    UNANSWERABLE,

    /**
     * The corpus states a value that CONTRADICTS what a model would answer from general knowledge.
     * Expect a RESOLVED answer carrying the corpus's value and not the prior's.
     *
     * <p>This is the only class that can tell grounding from luck. On an {@code ANSWERABLE} ticket a
     * genuinely retrieved answer and a well-informed guess produce the same string, so a correct
     * answer proves nothing about where it came from. Here they produce different strings, and the
     * difference is the measurement.
     */
    TRAP;

    /** @return the class this slice names, or empty for a slice that predates grounding measurement */
    public static Optional<GroundingClass> of(String slice) {
        return switch (slice) {
            case "answerable" -> Optional.of(ANSWERABLE);
            case "unanswerable" -> Optional.of(UNANSWERABLE);
            case "trap" -> Optional.of(TRAP);
            default -> Optional.empty();
        };
    }

    /** True when a correct run ANSWERS this ticket; false when a correct run refuses it. */
    public boolean expectsAnAnswer() {
        return this != UNANSWERABLE;
    }
}
