package org.aura.aura.retrieval;

import java.util.List;

/**
 * What retrieval hands to everything downstream: the exact bytes that go into the prompt, and the
 * ledger of what those bytes contain.
 *
 * <p>The two travel together in one object on purpose. They are two views of ONE fact — the surviving
 * chunk set — and the failure mode of letting them be assembled separately is a response whose
 * {@code sourcesProvided} lists a chunk the model never saw, or omits one it did. Nobody would notice:
 * both halves look right in isolation, and the only symptom is a citation that quietly does not
 * correspond to the answer. {@link ContextBlockAssembler} builds both from the same sorted list in
 * one pass, so they cannot disagree.
 *
 * @param rendered         the canonical context block — byte-identical for a given logical result.
 *                         This is what rides in the user turn AND what the response cache key hashes
 *                         (Decision 4), which is only sound because it is canonical.
 * @param sourcesProvided  the ledger, in the same canonical order
 */
public record ContextBlock(String rendered, List<SourceRef> sourcesProvided) {

    /** True when retrieval found nothing to ground the answer in — the block is the empty frame. */
    public boolean isEmpty() {
        return sourcesProvided.isEmpty();
    }
}
