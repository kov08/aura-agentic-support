package org.aura.aura.retrieval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical-assembly contract. Every test here is really one sentence: <b>the rendered block is a
 * function of the SET of surviving chunks and of nothing else.</b>
 *
 * <p>That property is load-bearing rather than tidy. Decision 4 hashes these bytes into the response
 * cache key, so anything that varies between two renderings of one logical result mints a second key
 * for it — and the symptom is not an error, it is a cache that reports a permanent miss rate while
 * every component in it reports healthy.
 */
class ContextBlockAssemblerTest {

    private final ContextBlockAssembler assembler = new ContextBlockAssembler();

    // Fixed ids, because a canonical representation cannot be asserted against random ones — and
    // because the tie-break below has to be predictable to be tested at all.
    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID ID_C = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    // ---------------------------------------------------------------- THE canonicality assertion

    @Test
    void shuffledRowsProduceIdenticalBytes() {
        List<RetrievedChunk> ordered = List.of(
                chunk(ID_A, "refund-policy.md", 0, "Refund Policy", "thirty days", 0.11),
                chunk(ID_B, "shipping-policy.md", 2, "Shipping > Canada", "five to seven days", 0.24),
                chunk(ID_C, "warranty-policy.md", 1, "Warranty > Claims", "two years", 0.37));

        // Every permutation, not one arbitrary shuffle. A single reversed list would pass against an
        // implementation that happened to sort only when the input was reversed; six of six is the
        // difference between "handles the case I thought of" and "does not depend on input order".
        List<String> renderings = permutations(ordered).stream()
                .map(rows -> assembler.assemble(rows).rendered())
                .toList();

        assertThat(renderings)
                .as("the rendered block must depend on the SET of chunks, never on their arrival order")
                .containsOnly(renderings.getFirst());
    }

    @Test
    void identicalBytesHashToTheSameCacheKeyMaterial() {
        // The consequence that makes the property above matter, asserted as the chain it actually is:
        // same set -> same bytes -> same digest. A test that stopped at "same bytes" would be
        // asserting an implementation detail; this asserts the thing that is downstream of it.
        List<RetrievedChunk> rows = List.of(
                chunk(ID_A, "refund-policy.md", 0, "Refund Policy", "thirty days", 0.11),
                chunk(ID_B, "shipping-policy.md", 2, "Shipping > Canada", "five to seven days", 0.24));

        String forward = assembler.assemble(rows).rendered();
        String reversed = assembler.assemble(rows.reversed()).rendered();

        assertThat(sha256(reversed)).isEqualTo(sha256(forward));
    }

    @Test
    void tiedDistancesAreBrokenByChunkIdSoTheOrderIsTotal() {
        // Two chunks at the SAME distance is not exotic — duplicated boilerplate across two policy
        // documents produces it. Sorting on distance alone would leave their relative order to the
        // sort's stability, i.e. to arrival order, i.e. to exactly the thing being eliminated.
        RetrievedChunk bb = chunk(ID_B, "b.md", 0, "B", "b body", 0.5);
        RetrievedChunk aa = chunk(ID_A, "a.md", 0, "A", "a body", 0.5);

        assertThat(assembler.assemble(List.of(bb, aa)).rendered())
                .isEqualTo(assembler.assemble(List.of(aa, bb)).rendered());
        assertThat(assembler.assemble(List.of(bb, aa)).sourcesProvided())
                .extracting(SourceRef::chunkId)
                .containsExactly(ID_A, ID_B);   // ...aa before bb, by id, regardless of how they arrived
    }

    // ---------------------------------------------------------------- the frame itself

    @Test
    void rendersOneTaggedElementPerChunkWithIdAndBreadcrumbAsAttributes() {
        ContextBlock block = assembler.assemble(List.of(
                chunk(ID_A, "refund-policy.md", 0, "Refund Policy > Standard Window",
                        "Customers have 30 days from the delivery date.", 0.11)));

        assertThat(block.rendered()).isEqualTo("""
                <documents>
                <document id="00000000-0000-0000-0000-0000000000aa" breadcrumb="Refund Policy > Standard Window">
                Customers have 30 days from the delivery date.
                </document>
                </documents>""");
    }

    @Test
    void contentIsRenderedVerbatimSoEveryCorpusByteIsInsideTheHash() {
        // Trailing whitespace is the cheapest possible probe for "does this normalise anything".
        // If the assembler trimmed, an edit that only touched whitespace would leave the cache key
        // unchanged and the pre-edit answer would keep being served for a full TTL.
        String padded = "  leading and trailing   ";
        ContextBlock block = assembler.assemble(
                List.of(chunk(ID_A, "a.md", 0, "A", padded, 0.1)));

        assertThat(block.rendered()).contains("\n" + padded + "\n");
    }

    @Test
    void distanceIsAbsentFromTheRenderedBytes() {
        // THE anti-nonce assertion. Distance is derived from an embedding, and embeddings are not
        // bit-reproducible — the Day 14 band measurement watched one sentence return distances
        // differing in the fourth decimal across identical calls. Let that number into the block and
        // it lands in the cache key, and the key never repeats: a 100% miss rate with nothing broken.
        double distance = 0.123456789;
        ContextBlock block = assembler.assemble(
                List.of(chunk(ID_A, "a.md", 0, "A", "body", distance)));

        assertThat(block.rendered()).doesNotContain("0.123456789").doesNotContain("distance");
        // ...while still reaching the wire through the ledger, which is not hashed.
        assertThat(block.sourcesProvided().getFirst().distance()).isEqualTo(distance);
    }

    @Test
    void anEmptySurvivorSetStillRendersAStableFrame() {
        ContextBlock block = assembler.assemble(List.of());

        // Not "" and not a prose sentence: a frame is hashable, byte-stable, and unambiguous to the
        // model — it is looking at an empty document list, which is exactly what happened.
        assertThat(block.rendered()).isEqualTo("<documents>\n</documents>");
        assertThat(block.isEmpty()).isTrue();
        assertThat(block.sourcesProvided()).isEmpty();
    }

    @Test
    void breadcrumbAttributesAreEscapedSoAQuoteCannotBreakTheFrame() {
        ContextBlock block = assembler.assemble(List.of(
                chunk(ID_A, "a.md", 0, "Policy & \"Terms\" <draft>", "body", 0.1)));

        assertThat(block.rendered())
                // `>` stays literal: it cannot terminate an attribute, and every breadcrumb in this
                // corpus is a `>`-separated heading path — escaping it would mangle all of them to buy
                // nothing.
                .contains("breadcrumb=\"Policy &amp; &quot;Terms&quot; &lt;draft>\"")
                // The ampersand introduced by &quot; must not itself get escaped a second time —
                // that is what the replacement ORDER in escapeAttribute is for.
                .doesNotContain("&amp;quot;");
        // The ledger keeps the ORIGINAL text: escaping is a property of the rendering, not of the
        // breadcrumb, and a customer-facing citation should not read "Policy &amp; Terms".
        assertThat(block.sourcesProvided().getFirst().breadcrumb()).isEqualTo("Policy & \"Terms\" <draft>");
    }

    // ---------------------------------------------------------------- the ledger

    @Test
    void theLedgerAndTheBlockDescribeTheSameChunksInTheSameOrder() {
        // The one-writer guarantee, checked directly: whatever ends up in sourcesProvided must be
        // exactly what ends up between the tags. A response citing a chunk the model never saw is
        // invisible in every other test, because both halves look right in isolation.
        List<RetrievedChunk> rows = List.of(
                chunk(ID_C, "c.md", 0, "C", "c body", 0.9),
                chunk(ID_A, "a.md", 0, "A", "a body", 0.1),
                chunk(ID_B, "b.md", 0, "B", "b body", 0.5));

        ContextBlock block = assembler.assemble(rows);

        assertThat(block.sourcesProvided())
                .extracting(SourceRef::chunkId)
                .containsExactly(ID_A, ID_B, ID_C);      // canonical order: ascending distance
        assertThat(block.rendered().indexOf("a body"))
                .isLessThan(block.rendered().indexOf("b body"));
        assertThat(block.rendered().indexOf("b body"))
                .isLessThan(block.rendered().indexOf("c body"));
    }

    // ---------------------------------------------------------------- fixtures

    private static RetrievedChunk chunk(UUID id, String doc, int index, String breadcrumb,
                                        String content, double distance) {
        return new RetrievedChunk(id, doc, index, breadcrumb, content, 10, distance);
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) return List.of(List.of());
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                out.add(permutation);
            }
        }
        return out;
    }

    private static String sha256(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
