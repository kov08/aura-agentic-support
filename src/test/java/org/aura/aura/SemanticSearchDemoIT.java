package org.aura.aura;

import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.ingest.KbCorpusLoader;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.KbChunk;
import org.aura.aura.util.VectorLiterals;
import org.aura.aura.util.VectorMath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end semantic search over the real {@code kb/} corpus, against the real Voyage API — now
 * retrieving from Postgres instead of from an ArrayList. NOT part of any automatic build: it is tagged
 * {@code "manual"} and excluded by Failsafe, exactly as the golden-set eval is tagged {@code "eval"}
 * and excluded by Surefire. It costs money, needs a live key, and its interesting output is a ranked
 * table a human reads, not a boolean a machine checks. Run it with {@code mvn verify -Pdemo} and a
 * real {@code VOYAGE_API_KEY}.
 *
 * <h2>What changed on Day 13</h2>
 * Day 12's version of this test chunked the corpus, embedded it, and then ranked it with a hand-written
 * loop over an in-memory list — "brute force, on purpose", with a note that pgvector replaces it. This
 * is that replacement, and the diff is the lesson: the chunking and the embedding are unchanged and
 * have moved into {@link KbCorpusLoader}; the ranking loop is gone entirely, replaced by
 * {@code ORDER BY embedding <=> ?} in {@link ChunkRepository#findNearest}. The index is not magic — it
 * was always this loop, and what the database adds is that the corpus no longer has to fit in the JVM
 * or be re-embedded on every restart.
 *
 * <p>The corpus IS re-embedded on every run here, because the container is fresh each time. That is a
 * property of running a demo against a throwaway database, not of the design: the same loader against
 * the compose Postgres skips an already-populated table and costs nothing.
 */
@Tag("manual")
@ActiveProfiles("test")   // suppresses ConversationRunner (@Profile("!test")), a dev-time live Claude call
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Opt back in to the database: application-test.yml keeps DataSourceAutoConfiguration
                // excluded so the DB-less majority of the suite needs no Docker. See PgVectorSchemaIT.
                "spring.autoconfigure.exclude=",
                // Creates the KbCorpusLoader bean AND fires it: its ApplicationRunner runs during
                // context startup, so the corpus is already ingested by the time the first test method
                // executes. (Measured, not assumed — an earlier revision of this comment claimed
                // runners do not fire under @SpringBootTest, and the demo's own log disproved it:
                // "load COMPLETE — 33 chunks" during startup, then "load SKIPPED" from the explicit
                // call below.) The test calls load() anyway, and that second call is the point: it is
                // the skip guard demonstrating itself against a corpus that is already populated.
                "aura.kb.load=true"
        })
@Testcontainers
class SemanticSearchDemoIT {

    private static final int TOP_K = 3;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private static final List<String> QUERIES = List.of(
            "Can I get my money back for a hoodie I bought two weeks ago?",
            "How long does delivery to Canada take?",
            // The control. It has no answer anywhere in the corpus, and the point of including it is
            // that it STILL returns three ranked results with plausible-looking scores.
            "Do you sell scuba diving gear?");

    @Autowired KbCorpusLoader loader;
    @Autowired VoyageEmbeddingClient voyage;
    @Autowired ChunkRepository chunks;
    @Autowired VoyageProperties props;

    @Test
    void rankKbChunksAgainstThreeQueries() {
        assertThat(props.apiKey())
                .as("SemanticSearchDemoIT makes REAL Voyage calls — export a real VOYAGE_API_KEY "
                        + "(the 'test' profile falls back to the dummy 'test-key' when it is absent)")
                .isNotEqualTo("test-key");

        // ---- 1. The corpus is already ingested, and this proves the guard ----------------------
        // Chunking, embedding, and persistence all live behind one call now, and startup already made
        // it (see the aura.kb.load note above). Calling it a second time must cost nothing: a
        // populated table means the expensive work is done, and the loader declines to redo it.
        KbCorpusLoader.LoadReport report = loader.load();
        assertThat(report.skipped())
                .as("a second load against a populated corpus must skip, not re-embed — that guard is "
                        + "the difference between a free re-run and a billable one")
                .isTrue();

        long stored = chunks.count();
        assertThat(stored).as("the corpus must have landed in kb_chunks").isPositive();

        System.out.printf("%n=== Day 13 semantic search demo — %d chunks in pgvector ===%n", stored);
        System.out.printf("document model=%s   query model=%s%n%n", props.documentModel(), props.queryModel());

        // ---- 2. One query at a time, with the economy query model ------------------------------
        for (String query : QUERIES) {
            float[] queryVector = voyage.embedQuery(query);

            // THE replacement. Postgres does the scoring, the sorting, and the truncation; the JVM
            // holds one vector and three rows instead of the entire corpus.
            List<KbChunk> top = chunks.findNearest(VectorLiterals.toLiteral(queryVector), TOP_K);

            System.out.println("query: " + query);
            for (KbChunk chunk : top) {
                // The ORDER above is pgvector's. This number recomputes the same quantity locally for
                // display — `<=>` is cosine distance, which is exactly 1 - cosine similarity — so the
                // printed distances are the values that produced the ranking, not a second opinion.
                double distance = 1.0 - VectorMath.cosineSimilarity(chunk.getEmbedding(), queryVector);
                System.out.printf("   %.4f  %-58s  [%s]%n",
                        distance, truncate(chunk.getBreadcrumb(), 58), chunk.getSourceDoc());
            }
            System.out.println();

            if (query.equals(QUERIES.getFirst())) {
                // ONE loose assertion, and deliberately loose. A refund question phrased with none of
                // the policy's vocabulary ("money back", "hoodie") must land in the refund document —
                // that is the capability under test. Asserting an exact chunk or a score threshold
                // would be asserting the model's current weights, which change without warning and
                // are not ours to pin.
                assertThat(top.getFirst().getBreadcrumb())
                        .as("a paraphrased refund question must rank a Refund Policy section first")
                        .contains("Refund");
            }
        }

        // Left as an observation rather than an assertion, and unchanged by the move to pgvector: the
        // off-topic scuba query still gets three ranked hits, because cosine distance is RELATIVE, not
        // calibrated. There is no absolute value that means "this is actually relevant" — a "best
        // match" is always returned, however bad the field. A vector database does not fix that; it
        // needs a threshold (Day 16) or a grounding check in the resolver prompt.
        System.out.println("NOTE: the off-topic query still returned a ranked 'best' match — distances are "
                + "relative, not calibrated. Relevance gating is a separate decision, not a free "
                + "property of retrieval, and moving the search into the database did not change that.");
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
