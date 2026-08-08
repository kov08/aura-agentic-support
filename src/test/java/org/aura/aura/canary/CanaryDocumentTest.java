package org.aura.aura.canary;

import org.aura.aura.domain.Chunk;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A LOCK, not a behaviour test. {@link CanaryDocument}'s text is the calibration sample for the band
 * in {@code application.yml}, so its exact bytes are load-bearing in a way ordinary constants are
 * not: change one character and the guard is comparing a fresh measurement against a threshold
 * measured for different text — a number checked against a threshold that describes something else.
 *
 * <p>The realistic ways that happens are all invisible in a diff review: an editor re-wrapping a long
 * line, a tool normalising the two em-dashes to hyphens, a file saved in a non-UTF-8 encoding, an
 * IDE stripping a trailing space. None of them look like an edit to the canary. All of them break it
 * silently. So the whole embedding input is pinned to one digest, and any of those failures becomes a
 * red test naming the reason instead of a boot that refuses three weeks later for no visible cause.
 *
 * <p>If this test fails and the change was DELIBERATE, the fix is not to update the digest on its
 * own. The band belongs to the text: re-run {@code CanaryBandHarnessIT}, paste the new band into
 * {@code application.yml}, and update the digest here in the same commit.
 *
 * <h2>What V4 changed, and what it did not</h2>
 * The vector these bytes produce now lives in {@code canary_probe} rather than in {@code kb_chunks},
 * so the canary is no longer reachable by retrieval. None of that touches the TEXT, which is why the
 * digest below is unchanged and the measured band carried over untouched.
 *
 * <p>One thing did get stricter, and it raises the stakes on this file. {@code RetrievalCanaryCheck}
 * used to re-embed prose read back out of the stored row; it now re-embeds this constant directly,
 * because the probe table stores a vector and no prose. So these bytes are the ONLY definition of
 * what the guard measures — there is no second copy in the database to fall back on, and an
 * undetected change here silently redefines the guard rather than merely disagreeing with it.
 */
class CanaryDocumentTest {

    /**
     * SHA-256 of {@code CanaryDocument.fingerprintContent()} as UTF-8.
     *
     * <p>Recorded 2026-08-06, when the text was copied verbatim out of {@code refund-policy.md}
     * chunk 0 — the chunk the band was measured against on 2026-08-04.
     */
    private static final String FROZEN_DIGEST =
            "d203ab9b80c0f4fe4f27f61fe2d4239252db29f846ad71930525723da4aa5a49";

    @Test
    void theEmbeddedTextIsByteForByteWhatTheBandWasMeasuredAgainst() {
        assertThat(sha256(CanaryDocument.fingerprintContent()))
                .as("""
                        the canary's embedding input has changed. The band in application.yml \
                        (aura.canary.band) was measured against the previous bytes and no longer \
                        describes this text, so the boot guard is now comparing a measurement to a \
                        threshold for something else. If the change was deliberate: re-run \
                        CanaryBandHarnessIT, paste the new band, and update FROZEN_DIGEST here in \
                        the same commit.""")
                .isEqualTo(FROZEN_DIGEST);
    }

    @Test
    void theShapeOfTheTextIsWhatTheDigestClaims() {
        // Redundant with the digest, and kept because a digest mismatch says only "something moved".
        // These three say WHICH thing, which is the difference between a two-minute fix and a
        // bisect: 428 characters, four newlines, two em-dashes.
        assertThat(CanaryDocument.TEXT).hasSize(428);
        assertThat(CanaryDocument.TEXT.chars().filter(c -> c == '\n').count()).isEqualTo(4);
        assertThat(CanaryDocument.TEXT.chars().filter(c -> c == 0x2014).count())
                .as("two U+2014 em-dashes — a tool that 'helpfully' converts them to hyphens is the "
                        + "likeliest silent corruption of this constant")
                .isEqualTo(2);
        assertThat(CanaryDocument.TEXT)
                .as("no CR anywhere: this constant is source code, not a checked-out file, so it must "
                        + "not inherit the line-ending ambiguity kb/*.md has")
                .doesNotContain("\r");
    }

    @Test
    void theFingerprintContentIsTheBreadcrumbAndTheBodyJoinedTheOneCanonicalWay() {
        // Never a hand-rolled breadcrumb + "\n" + text. Chunk.embeddingInput is the single definition
        // of what gets embedded, and a second one here would drift the day the separator changes —
        // moving the canary's measured distance for a reason that has nothing to do with the lanes.
        assertThat(CanaryDocument.fingerprintContent())
                .isEqualTo(Chunk.embeddingInput(CanaryDocument.BREADCRUMB, CanaryDocument.TEXT))
                .isEqualTo(CanaryDocument.chunk().embeddingInput());
    }

    @Test
    void theSyntheticPathCannotCollideWithAScannedFile() {
        // The canary still shares a namespace with file names scanned out of kb/ — V4 moved its
        // VECTOR out of kb_chunks but deliberately left its row in kb_documents, so that it keeps
        // flowing through the same scan, plan and transaction as a real document. A collision would
        // put a policy document and the canary in one ledger row.
        assertThat(CanaryDocument.PATH).doesNotEndWith(".md").startsWith("__");
    }

    @Test
    void theChunkCarriesTheFrozenBreadcrumbRatherThanThePath() {
        // The reason IngestionPipeline bypasses DocumentChunker for this one document: the chunker
        // falls back to the file name when a document has no headings, so it would produce breadcrumb
        // "__canary__" and a completely different embedding input.
        Chunk chunk = CanaryDocument.chunk();

        assertThat(chunk.breadcrumb()).isEqualTo(CanaryDocument.BREADCRUMB).isNotEqualTo(CanaryDocument.PATH);
        assertThat(chunk.sourceDoc()).isEqualTo(CanaryDocument.PATH);
        assertThat(chunk.position()).isEqualTo(CanaryDocument.CHUNK_INDEX);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
