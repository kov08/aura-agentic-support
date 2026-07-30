package org.aura.aura.chunker;

import org.aura.aura.config.VoyageProperties;
import org.aura.aura.domain.Chunk;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the chunker — no Spring, no network, no Voyage key. Chunking is deterministic
 * logic over a string, and it is the layer where a silent mistake is most expensive: a chunk that
 * spans two topics, or a breadcrumb that names the wrong section, degrades every retrieval downstream
 * without ever throwing.
 *
 * <p>The size knobs are deliberately TINY here (200-char cap, 50-char overlap) so a few sentences of
 * fixture text exercise the same recursive fallback that a 3,000-character policy section triggers in
 * production. Testing the mechanism at production sizes would need a page of prose per assertion.
 */
class DocumentChunkerTest {

    private static final int CAP = 200;
    private static final int OVERLAP = 50;

    private final DocumentChunker chunker = new DocumentChunker(
            new VoyageProperties("test-key", null, null, null,
                    Duration.ofSeconds(1), Duration.ofSeconds(1), CAP, OVERLAP));

    // ------------------------------------------------------------------ structure

    @Test
    void smallSectionBecomesExactlyOneChunkWithTheFullHeadingPath() {
        String doc = """
                # Refund Policy

                Refunds are money coming back to the customer.

                ## International Orders

                Return shipping abroad is the customer's responsibility.
                """;

        List<Chunk> chunks = chunker.chunk(doc, "refund-policy.md");

        assertThat(chunks).hasSize(2);

        // The H1's own prose is its own chunk, breadcrumbed by the H1 alone.
        assertThat(chunks.get(0).breadcrumb()).isEqualTo("Refund Policy");
        assertThat(chunks.get(0).text()).isEqualTo("Refunds are money coming back to the customer.");
        assertThat(chunks.get(0).position()).isZero();

        // The nested section carries the FULL path — this is the breadcrumb the demo prints and the
        // string that gets embedded alongside the body.
        assertThat(chunks.get(1).breadcrumb()).isEqualTo("Refund Policy > International Orders");
        assertThat(chunks.get(1).sourceDoc()).isEqualTo("refund-policy.md");
        assertThat(chunks.get(1).position()).isEqualTo(1);

        // No "(part i/n)" suffix when the section fits: a citation reads as the section it actually is.
        assertThat(chunks).allSatisfy(c -> assertThat(c.breadcrumb()).doesNotContain("(part"));
    }

    @Test
    void embeddingInputPrependsTheBreadcrumbToTheBody() {
        Chunk chunk = chunker.chunk("# Refund Policy\n\nThirty days from delivery.\n", "refund-policy.md")
                .getFirst();

        // The one authoritative definition of what gets embedded. Asserted here so an "improvement" to
        // the separator or the ordering has to walk past a failing test rather than silently shifting
        // every similarity score in the corpus.
        assertThat(chunk.embeddingInput()).isEqualTo("Refund Policy\nThirty days from delivery.");
    }

    @Test
    void aHeadingWithNoBodyProducesNoChunk() {
        // Real kb/ data does this: "## Delivery Problems" is a pure container for "### Lost Parcels".
        // Embedding a two-word heading with no content would create a vector that competes with the
        // real answers on every query.
        String doc = """
                # Shipping Policy

                ## Delivery Problems

                ### Lost Parcels

                Seven business days with no movement means we reship or refund.
                """;

        List<Chunk> chunks = chunker.chunk(doc, "shipping-policy.md");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().breadcrumb())
                .isEqualTo("Shipping Policy > Delivery Problems > Lost Parcels");
    }

    // ------------------------------------------------------------------ recursive fallback

    @Test
    void oversizedSectionSplitsIntoNumberedPartsThatAllFitTheCap() {
        String doc = "# Refund Policy\n\n## International Orders\n\n" + sentences("Rule", 20);

        List<Chunk> chunks = chunker.chunk(doc, "refund-policy.md");

        assertThat(chunks).hasSizeGreaterThan(1);

        // (1) EVERY part is under the cap. This is the guarantee the packing-to-(cap - overlap) design
        //     buys: the overlap prefix can never push a chunk over the limit, because its budget was
        //     reserved before the body was packed.
        assertThat(chunks).allSatisfy(c ->
                assertThat(c.text().length())
                        .as("chunk %d must fit the %d-char cap", c.position(), CAP)
                        .isLessThanOrEqualTo(CAP));

        // (2) Part numbering is correct and complete: 1/n .. n/n, all under the SAME section path.
        int n = chunks.size();
        for (int i = 0; i < n; i++) {
            assertThat(chunks.get(i).breadcrumb())
                    .isEqualTo("Refund Policy > International Orders (part " + (i + 1) + "/" + n + ")");
            assertThat(chunks.get(i).position()).isEqualTo(i);
        }
    }

    @Test
    void consecutivePartsShareRoughlyTheConfiguredOverlap() {
        List<Chunk> chunks = chunker.chunk(
                "# Refund Policy\n\n## International Orders\n\n" + sentences("Rule", 20),
                "refund-policy.md");

        assertThat(chunks).hasSizeGreaterThan(1);

        for (int i = 1; i < chunks.size(); i++) {
            String shared = sharedOverlap(chunks.get(i - 1).text(), chunks.get(i).text());
            // A band, not an exact number: the overlap is snapped FORWARD to a word boundary, so it is
            // always a little shorter than the budget and never longer. Asserting a range is asserting
            // the actual contract ("about this much, never over"); asserting an exact length would be
            // asserting an accident of where the fixture's spaces happen to fall.
            assertThat(shared.length())
                    .as("parts %d and %d must overlap by roughly %d chars", i - 1, i, OVERLAP)
                    .isBetween(OVERLAP / 2, OVERLAP);
        }
    }

    @Test
    void noChunkEverSpansAHeadingBoundary() {
        // Two oversized sections, each written in a vocabulary the other never uses. If overlap or
        // packing ever leaked across the heading, a chunk would contain both markers — and its
        // breadcrumb would then be a lie about half its content.
        String doc = "# Refund Policy\n\n"
                + "## Alpha Section\n\n" + sentences("ALPHA", 20) + "\n\n"
                + "## Beta Section\n\n" + sentences("BETA", 20);

        List<Chunk> chunks = chunker.chunk(doc, "refund-policy.md");

        assertThat(chunks).allSatisfy(c -> {
            boolean alpha = c.text().contains("ALPHA");
            boolean beta = c.text().contains("BETA");
            assertThat(alpha && beta)
                    .as("chunk %d (%s) mixes two sections", c.position(), c.breadcrumb())
                    .isFalse();
            // and the breadcrumb must agree with whichever vocabulary it actually holds
            assertThat(c.breadcrumb()).contains(alpha ? "Alpha Section" : "Beta Section");
        });
    }

    @Test
    void hardCutIsTheBaseCaseForTextWithNoBoundariesAtAll() {
        // No blank line, no sentence punctuation, no space — every separator in the hierarchy fails and
        // the character cut has to terminate the recursion. Pathological, but a base64 blob or a
        // mangled table in a policy file looks exactly like this.
        String blob = "x".repeat(CAP * 3);

        List<Chunk> chunks = chunker.chunk("# Refund Policy\n\n" + blob, "refund-policy.md");

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(CAP));
        // Reassembly sanity: nothing was dropped on the floor by the cut.
        assertThat(String.join("", chunks.stream().map(Chunk::text).toList())).contains("x".repeat(CAP));
    }

    // ------------------------------------------------------------------ degenerate inputs

    @Test
    void documentWithNoHeadingsIsOneRootSectionBreadcrumbedByTheFileName() {
        String doc = sentences("Rule", 20);   // no '#' anywhere

        List<Chunk> chunks = chunker.chunk(doc, "loose-notes.md");

        assertThat(chunks).hasSizeGreaterThan(1);   // still split — the cap does not care about headings
        // The file name is the only name the author gave us, so it becomes the path.
        assertThat(chunks).allSatisfy(c -> assertThat(c.breadcrumb()).startsWith("loose-notes.md"));
        assertThat(chunks.getFirst().breadcrumb()).isEqualTo("loose-notes.md (part 1/" + chunks.size() + ")");
    }

    @Test
    void emptyOrBlankDocumentYieldsNoChunks() {
        assertThat(chunker.chunk("", "empty.md")).isEmpty();
        assertThat(chunker.chunk("   \n\n\t\n", "empty.md")).isEmpty();
        assertThat(chunker.chunk(null, "empty.md")).isEmpty();
        // A file that is nothing but its maintainer comment is also empty AFTER stripping — the comment
        // is metadata, and metadata must not become a retrievable chunk.
        assertThat(chunker.chunk("<!-- just a note about ADR-007a -->\n", "note.md")).isEmpty();
    }

    // ------------------------------------------------------------------ the real corpus

    @Test
    void theRealKbCorpusStillContainsAnOversizedSectionAtProductionSizes() throws Exception {
        // Offline (no Voyage key, no network) but on REAL data, at the REAL configured sizes — the
        // synthetic fixtures above prove the mechanism, this proves the corpus still exercises it.
        //
        // kb/refund-policy.md's "International Orders" section is deliberately oversized so the
        // recursive fallback runs on genuine prose rather than only on test strings. Nothing stops an
        // editor from tightening that section next month, at which point the fallback would quietly
        // stop being exercised by the demo and nobody would notice. This test is that notice.
        DocumentChunker production = new DocumentChunker(
                new VoyageProperties("test-key", null, null, null, null, null, 2000, 300));

        List<Chunk> chunks = production.chunk(
                Files.readString(Path.of("kb", "refund-policy.md")), "refund-policy.md");

        assertThat(chunks).allSatisfy(c -> assertThat(c.text().length()).isLessThanOrEqualTo(2000));
        assertThat(chunks)
                .as("kb/refund-policy.md must keep one section big enough to split")
                .anySatisfy(c -> assertThat(c.breadcrumb()).contains("International Orders (part 1/"));
        // Every other section stays whole — a corpus where everything splits would mean the cap, not
        // the author's structure, is choosing the boundaries.
        assertThat(chunks.stream().filter(c -> c.breadcrumb().contains("(part")).toList())
                .allSatisfy(c -> assertThat(c.breadcrumb()).contains("International Orders"));
    }

    // ------------------------------------------------------------------ fixtures

    /** {@code n} distinct sentences in one paragraph, each ~60 chars — big enough to force a split. */
    private static String sentences(String marker, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            if (i > 1) sb.append(' ');
            sb.append("%s rule %02d applies to every order placed in region %02d.".formatted(marker, i, i));
        }
        return sb.toString();
    }

    /** The longest suffix of {@code previous} that is also a prefix of {@code next}. */
    private static String sharedOverlap(String previous, String next) {
        int max = Math.min(previous.length(), next.length());
        for (int len = max; len > 0; len--) {
            if (next.startsWith(previous.substring(previous.length() - len))) {
                return next.substring(0, len);
            }
        }
        return "";
    }
}
