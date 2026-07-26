package org.aura.aura.evals;

import java.util.List;

/**
 * The whole golden set as loaded from {@code golden-set-v2.json}: a version, the {@link LabelingPolicy}
 * that governs its labels, and the tickets. {@code labelingPolicy} is a non-null component on purpose —
 * a v2 set with the policy omitted deserializes it to null and {@link GoldenSetIntegrityTest} fails
 * loudly, which is the point: the law is what makes the relabels reviewable rather than arbitrary.
 */
public record GoldenSet(int goldenSetVersion, LabelingPolicy labelingPolicy, List<EvalTicket> tickets) {}
