package org.aura.aura.domain;

/**
 * One retrievable unit of the knowledge base: a slice of a source document, plus the heading path it
 * came from.
 *
 * @param text       the chunk body, with the heading line itself excluded (it lives in the breadcrumb)
 * @param breadcrumb the full heading path, e.g. {@code "Refund Policy > International Orders"};
 *                   sub-chunks of one oversized section carry a {@code " (part i/n)"} suffix
 * @param sourceDoc  the file the chunk came from, e.g. {@code "refund-policy.md"} — the citation
 *                   handle the resolver will quote back as a source
 * @param position   the chunk's 0-based ordinal within its source document, in reading order
 */
public record Chunk(String text, String breadcrumb, String sourceDoc, int position) {

    /**
     * The SINGLE authoritative definition of what exactly gets embedded — one-writer-per-field applied
     * to the embedding input.
     *
     * <p>Ingestion (Day 15) and query time must embed text built the same way, or the vectors are
     * comparable only by accident. The temptation is to inline {@code breadcrumb + "\n" + text} at the
     * one call site that needs it today; the failure mode is that the second call site inlines it
     * slightly differently six days later and every similarity score shifts for reasons no one can
     * see in a diff. So the concatenation is a method on the chunk itself, and callers never build
     * embedding input by hand.
     *
     * <p>The breadcrumb is included deliberately: a chunk reading "Return shipping is the customer's
     * responsibility…" is nearly meaningless stripped of its heading path, and prepending
     * "Refund Policy > International Orders" is what lets a query about refunds abroad find it. The
     * heading is context the chunk body no longer carries, because the chunker removed the heading
     * line from the text — that removal and this restoration are two halves of one decision.
     */
    public String embeddingInput() {
        return breadcrumb + "\n" + text;
    }
}
