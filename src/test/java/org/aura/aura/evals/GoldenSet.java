package org.aura.aura.evals;

import java.util.List;

/** The whole golden set as loaded from {@code golden-set-v1.json}: a version and its tickets. */
public record GoldenSet(int goldenSetVersion, List<EvalTicket> tickets) {}
