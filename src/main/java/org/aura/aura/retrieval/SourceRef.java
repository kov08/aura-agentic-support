package org.aura.aura.retrieval;

import java.util.UUID;

/**
 * One entry in the grounding ledger: which chunk was put in front of the model, what a human should
 * call it, and how near it actually was.
 *
 * <h2>One writer per field</h2>
 * These are produced by {@link ContextBlockAssembler} from the surviving chunk set and by nothing
 * else. In particular they are NOT read out of the model's reply. If the answer's prose says "per the
 * refund policy", that is narration — the model describing what it believes it used. This list is the
 * ledger: what was actually in the request. The two can disagree, and when they do the ledger is
 * right, because it is a record of an event rather than a claim about one.
 *
 * <p>That is also why {@code SourceRef} is absent from {@code ResolverOutput}'s schema. A field the
 * model can write is a field the model can be wrong about, and a citation nobody can trust is worse
 * than no citation — it looks like evidence.
 *
 * @param chunkId    the stored chunk's uuid, exactly as it appears in the rendered context block, so
 *                   a citation can be traced back to the bytes the model was shown
 * @param breadcrumb the heading path, e.g. {@code "Refund Policy > Standard Refund Window"} — the
 *                   human-readable half, and the only part of this record worth showing a customer
 * @param distance   pgvector's cosine distance for this chunk against this ticket's query vector,
 *                   projected by the search itself (Decision 3A). Smaller is nearer. It is on the
 *                   wire because an unexplained ranking is not a trust signal: "0.19" and "0.61" tell
 *                   a support engineer whether the answer was grounded or merely decorated.
 */
public record SourceRef(UUID chunkId, String breadcrumb, double distance) {
}
