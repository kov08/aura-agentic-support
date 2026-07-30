package org.aura.aura;

import org.aura.aura.chunker.DocumentChunker;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.domain.Chunk;
import org.aura.aura.util.VectorMath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end semantic search over the real {@code kb/} corpus, against the real Voyage API. NOT part
 * of any automatic build: it is tagged {@code "manual"} and excluded by Failsafe, exactly as the
 * golden-set eval is tagged {@code "eval"} and excluded by Surefire — same reason, too. It costs
 * money, needs a live key, and its interesting output is a ranked table a human reads, not a boolean
 * a machine checks. Run it with {@code mvn verify -Pdemo} and a real {@code VOYAGE_API_KEY}.
 *
 * <p>What it proves that the unit tests cannot: that chunking, breadcrumbs, the asymmetric model pair,
 * and cosine ranking compose into retrieval that actually works on prose nobody wrote for the test —
 * a paraphrased question with almost no lexical overlap with its answer. That is precisely the failure
 * the Day 4 keyword knowledge base had (a reworded return question retrieved nothing), so this is the
 * first evidence the RAG track fixes it.
 *
 * <h2>Brute force, on purpose</h2>
 * The ranking below is a full linear scan: every query is compared against every chunk in memory.
 * That is exactly what pgvector replaces tomorrow (Day 13), and writing it out by hand once is the
 * cheapest way to make the replacement legible — the index is not magic, it is this loop with an
 * ANN structure and persistence bolted on. At ~50 chunks the loop is instant; at 50,000 it is the
 * whole latency budget.
 */
@Tag("manual")
@ActiveProfiles("test")   // suppresses ConversationRunner (@Profile("!test")), a dev-time live Claude call
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SemanticSearchDemoIT {

    private static final Path KB = Path.of("kb");
    private static final int TOP_K = 3;

    private static final List<String> QUERIES = List.of(
            "Can I get my money back for a hoodie I bought two weeks ago?",
            "How long does delivery to Canada take?",
            // The control. It has no answer anywhere in the corpus, and the point of including it is
            // that it STILL returns three ranked results with plausible-looking scores.
            "Do you sell scuba diving gear?");

    @Autowired DocumentChunker chunker;
    @Autowired VoyageEmbeddingClient voyage;
    @Autowired VoyageProperties props;

    @Test
    void rankKbChunksAgainstThreeQueries() {
        assertThat(props.apiKey())
                .as("SemanticSearchDemoIT makes REAL Voyage calls — export a real VOYAGE_API_KEY "
                        + "(the 'test' profile falls back to the dummy 'test-key' when it is absent)")
                .isNotEqualTo("test-key");

        // ---- 1. Chunk the real corpus -------------------------------------------------------
        List<Chunk> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(KB)) {
            files.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> chunks.addAll(chunker.chunk(read(p), p.getFileName().toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("kb/ not found — this test runs from the module directory", e);
        }
        assertThat(chunks).as("kb/ must contain chunkable markdown").isNotEmpty();

        // ---- 2. Embed the corpus ONCE, with the premium document model ------------------------
        // One batch call, one time. This is the offline lane, and its cost is amortised over every
        // query that will ever run against it — which is the entire argument for spending the better
        // model here and the cheaper one below.
        List<float[]> corpus = voyage.embedDocuments(chunks.stream().map(Chunk::embeddingInput).toList());
        assertThat(corpus).hasSameSizeAs(chunks);

        System.out.printf("%n=== Day 12 semantic search demo — %d chunks from %s, dim=%d ===%n",
                chunks.size(), KB, corpus.getFirst().length);
        System.out.printf("document model=%s   query model=%s%n%n", props.documentModel(), props.queryModel());

        // ---- 3. One query at a time, with the economy query model -----------------------------
        for (String query : QUERIES) {
            float[] queryVector = voyage.embedQuery(query);

            List<Ranked> top = rank(chunks, corpus, queryVector);

            System.out.println("query: " + query);
            for (Ranked r : top) {
                System.out.printf("   %.4f  %-58s  [%s]%n",
                        r.score(), truncate(r.chunk().breadcrumb(), 58), r.chunk().sourceDoc());
            }
            System.out.println();

            if (query.equals(QUERIES.getFirst())) {
                // ONE loose assertion, and deliberately loose. A refund question phrased with none of
                // the policy's vocabulary ("money back", "hoodie") must land in the refund document —
                // that is the capability under test. Asserting an exact chunk or a score threshold
                // would be asserting the model's current weights, which change without warning and
                // are not ours to pin.
                assertThat(top.getFirst().chunk().breadcrumb())
                        .as("a paraphrased refund question must rank a Refund Policy section first")
                        .contains("Refund");
            }
        }

        // Left as an observation rather than an assertion: the off-topic scuba query still gets three
        // ranked hits, because cosine similarity is RELATIVE, not calibrated. There is no absolute
        // score that means "this is actually relevant" — a "best match" is always returned, however
        // bad the field. Turning that into a refusal needs a threshold (Day 16) or a grounding check
        // in the resolver prompt, not a better index.
        System.out.println("NOTE: the off-topic query still returned a ranked 'best' match — scores are "
                + "relative, not calibrated. Relevance gating is a separate decision, not a free "
                + "property of retrieval.");
    }

    private record Ranked(Chunk chunk, double score) {}

    // Brute-force top-k: score everything, sort, take K. See the class javadoc — this loop IS the
    // thing pgvector replaces, and its shape (score → sort → truncate) survives the replacement.
    private static List<Ranked> rank(List<Chunk> chunks, List<float[]> corpus, float[] query) {
        List<Ranked> scored = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            scored.add(new Ranked(chunks.get(i), VectorMath.cosineSimilarity(corpus.get(i), query)));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Ranked::score).reversed())
                .limit(TOP_K)
                .toList();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
