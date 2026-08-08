package org.aura.aura.canary;

import org.aura.aura.domain.Chunk;

/**
 * The canary's text, frozen in code — a synthetic one-chunk document that the ingestion pipeline
 * writes alongside the real corpus.
 *
 * <h2>Why the canary stopped being a corpus chunk</h2>
 * Day 14 pinned {@link RetrievalCanaryCheck} at {@code refund-policy.md#0} and measured the healthy
 * band against that chunk's exact embedding input. {@code CanaryProperties} states the rule that
 * makes this class necessary: <em>the band belongs to the text, not to the position</em>. As long as
 * the corpus only ever changed by hand, that was a stable arrangement.
 *
 * <p>Day 15 removes the "by hand". The pipeline now rebuilds chunks automatically whenever a document
 * changes, so an editor adding one sentence to the top of {@code refund-policy.md} silently changes
 * what chunk 0 SAYS — and the boot canary then refuses to start, correctly reporting a distance
 * outside its band, for a reason that has nothing to do with the embedding space it exists to guard.
 * A tripwire that fires on ordinary policy edits is a tripwire that gets disabled.
 *
 * <p>So the canary gets its own document, and the text is a constant rather than a file: a constant
 * cannot be edited by someone updating a refund window, and changing it is a code review.
 *
 * <h2>Why THIS text</h2>
 * It is byte-for-byte the text {@code refund-policy.md} chunk 0 held when the band in
 * {@code application.yml} was measured (2026-08-04, {@code CanaryBandHarnessIT}, n=20). Copying it
 * rather than inventing a fresh sentence is what lets the measured band carry over unchanged —
 * a new sentence would need a new measurement run before it could guard anything, and until then the
 * canary would be a number compared against a threshold nobody has calibrated.
 *
 * <p>Two consequences are accepted rather than hidden:
 *
 * <ul>
 *   <li><b>This row duplicates {@code refund-policy.md} chunk 0 in the retrieval corpus.</b> A refund
 *       query can match both and spend part of its context budget twice on one passage. At a 700-token
 *       budget and a ~140-token chunk that is roughly a fifth of one ticket's context — real, small,
 *       and fixable in one {@code WHERE source_doc <> …} on the hot-path query when it is worth
 *       touching that query for.</li>
 *   <li><b>The band's continued validity is an empirical question, not a proof.</b> V3 deletes every
 *       Day 13 chunk, so the stored side of the comparison is a FRESH document-lane vector on the
 *       first Day 15 run rather than the exact vector the band was measured against. Voyage was
 *       measured as very nearly deterministic (spread ~0.0007 over n=20), so it should land inside;
 *       the honest verification is booting with {@code aura.canary.enabled=true} and reading the
 *       distance the check logs at INFO. If it lands outside, the fix is a re-run of
 *       {@code CanaryBandHarnessIT} and a config diff — never a hand-widened band.</li>
 * </ul>
 */
public final class CanaryDocument {

    /**
     * The synthetic document's path, and therefore {@code kb_chunks.source_doc} and
     * {@code kb_documents.path} for its one row.
     *
     * <p>The double underscores are load-bearing: this value shares a namespace with real file names
     * scanned out of {@code kb/}, and a collision would put a policy document and the canary in one
     * ledger row. The pattern is also a signal to a human reading a citation that this row did not
     * come from a file.
     */
    public static final String PATH = "__canary__";

    /** Its only chunk's position. There is one chunk, so there is one position. */
    public static final int CHUNK_INDEX = 0;

    /**
     * The breadcrumb, which is HALF THE EMBEDDED TEXT — {@link Chunk#embeddingInput()} prepends it to
     * the body. Changing this string changes the vector exactly as surely as changing the prose does,
     * which is why it is frozen here beside the text rather than derived from {@link #PATH}.
     */
    public static final String BREADCRUMB = "Refund Policy";

    /**
     * The body: 428 characters, four newlines, two em-dashes (U+2014).
     *
     * <p>Written as explicit {@code \n} concatenation rather than a text block, because a text block's
     * incidental-indentation stripping and trailing-newline rules are two more ways for these exact
     * bytes to move without anyone editing them. {@code CanaryDocumentTest} pins the whole thing to a
     * SHA-256, so an editor that re-wraps a line or an encoding that mangles an em-dash fails a test
     * instead of quietly re-pointing the guard.
     */
    public static final String TEXT =
            "This document is the authoritative statement of when a ShopFast customer can get their money back,\n"
                    + "how much of it comes back, and how long it takes to arrive. Returns and refunds are related but\n"
                    + "separate: a return is the physical movement of an item back to us, a refund is the movement of money\n"
                    + "back to the customer. Most refunds require a return first, but some — a cancelled order, a duplicate\n"
                    + "charge, a lost parcel — do not.";

    private CanaryDocument() {
    }

    /**
     * The chunk the pipeline writes — built here rather than by {@link org.aura.aura.chunker.DocumentChunker}.
     *
     * <p>Running the chunker over this text would be the consistent-looking choice and the wrong one.
     * The chunker derives a breadcrumb from headings, falling back to the file name; handed
     * {@code __canary__} with no heading, it would produce breadcrumb {@code "__canary__"} and the
     * embedding input would become {@code "__canary__\nThis document is…"} — a different string, a
     * different vector, and a band measured against text that no longer exists. The canary is the one
     * document whose chunking must not be a function of anything.
     */
    public static Chunk chunk() {
        return new Chunk(TEXT, BREADCRUMB, PATH, CHUNK_INDEX);
    }

    /**
     * What the fingerprint is computed over: the exact string that gets embedded.
     *
     * <p>Not {@link #TEXT} alone. The breadcrumb is part of the embedding input, so hashing only the
     * body would leave a breadcrumb edit invisible to the pipeline — the fingerprint would match, the
     * document would be reported unchanged, and the stored vector would keep describing text nobody
     * writes any more.
     */
    public static String fingerprintContent() {
        return Chunk.embeddingInput(BREADCRUMB, TEXT);
    }
}
