package org.aura.aura.evals;

/**
 * One golden-set ticket: an input message and its hand-reviewed {@link ExpectedResult}. Jackson maps
 * this straight from {@code golden-set-v2.json}; the field names ARE the JSON keys.
 *
 * <p>{@code slice} is the failure family the ticket probes (clean, ambiguous, out_of_scope,
 * injection, garbage, noisy, whiff) — carried through to the report so accuracy can be read
 * per-slice, which is where the interesting signal lives.
 */
public record EvalTicket(
        String id,
        String slice,
        String customerMessage,
        ExpectedResult expected
) {}
