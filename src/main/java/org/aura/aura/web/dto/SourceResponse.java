package org.aura.aura.web.dto;

import org.aura.aura.retrieval.SourceRef;

/**
 * The wire shape of one grounding-ledger entry.
 *
 * <p>A near-copy of {@link SourceRef}, and the duplication is bought on purpose — the same trade
 * {@link ResolutionResponse} already makes against the domain {@code Resolution}. The rule this
 * package enforces is "nothing reaches the client unless it is a field here"; reusing the domain
 * record would hand that rule away, because the next field added to {@code SourceRef} for internal
 * reasons would appear on a public API by default rather than by decision. One mapping method is a
 * cheap price for keeping "what we know" and "what we publish" separately editable.
 *
 * @param chunkId    the chunk's uuid, matching the {@code id} attribute in the context block the
 *                   model was shown — so a support engineer can go from a response to the exact
 *                   bytes that produced it
 * @param breadcrumb the heading path; the human-readable half
 * @param distance   cosine distance, smaller is nearer. Published deliberately: without it, a list of
 *                   sources is a trust signal that cannot be checked. Distances are RELATIVE and
 *                   never calibrated — retrieval always returns a best match, even for a question the
 *                   corpus cannot answer — so the number is what lets a reader tell a grounded answer
 *                   from a decorated one.
 */
public record SourceResponse(String chunkId, String breadcrumb, double distance) {

    public static SourceResponse from(SourceRef ref) {
        return new SourceResponse(ref.chunkId().toString(), ref.breadcrumb(), ref.distance());
    }
}
