package org.aura.aura.canary;

import org.aura.aura.chunker.DocumentChunker;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.EmbeddingProperties;
import org.aura.aura.config.IngestionProperties;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.ingest.IngestReport;
import org.aura.aura.ingest.IngestionPipeline;
import org.aura.aura.store.CanaryProbeRepository;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.DocumentRepository;
import org.aura.aura.store.KbChunk;
import org.aura.aura.store.KbDocument;
import org.aura.aura.store.NearestChunk;
import org.aura.aura.util.VectorLiterals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V4's claim, against real Postgres, real migrations and the real pipeline: the canary is measurable
 * and unreachable at the same time.
 *
 * <h2>Why Voyage is stubbed rather than called</h2>
 * The question here is not "does the embedding model rank the canary highly" — it is "can a query
 * reach the canary at all". A stub answers that better than the live provider does, because it lets
 * the test construct the WORST case on purpose: here the canary's vector and the refund document's
 * vector are made byte-identical, so if the canary were still a chunk it would tie for first place on
 * every refund query. Live embeddings would only make them merely similar, and the test would then be
 * billable, rate-limited, and weaker.
 *
 * <p>Everything below the embedding call is real: real migrations including V4, real
 * {@code kb_documents} / {@code kb_chunks} / {@code canary_probe} tables, real transactions, and the
 * real {@code <=>} operator doing the ranking.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Opt back in to the database; application-test.yml excludes it for the DB-less majority.
        properties = "spring.autoconfigure.exclude=")
@ActiveProfiles({"test", "it"})
@Testcontainers
class CanaryIsolationIT {

    private static final int DIM = KbChunk.EMBEDDING_DIMENSION;
    private static final String DOCUMENT_MODEL = "voyage-4-large";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @TempDir Path corpus;

    @Autowired ChunkRepository chunks;
    @Autowired DocumentRepository documents;
    @Autowired CanaryProbeRepository probe;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private VoyageProperties voyageProps;
    private DocumentChunker chunker;
    private VoyageEmbeddingClient voyage;

    /**
     * The adversarial vector. EVERY embedding this stub returns is the same, so the canary and the
     * refund policy occupy the identical point in the space — the worst case the isolation has to
     * survive, and one live embeddings could not be relied upon to produce.
     */
    private static final float[] ONE_POINT = unit(0);

    @BeforeEach
    void reset() throws IOException {
        // Order matters: chunks reference documents, and canary_probe is independent of both.
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
        jdbc.update("DELETE FROM canary_probe");

        voyageProps = new VoyageProperties("test-key", null, DOCUMENT_MODEL, "voyage-4-lite",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 2000, 300);
        chunker = new DocumentChunker(voyageProps);

        voyage = mock(VoyageEmbeddingClient.class);
        when(voyage.embedBatched(anyList())).thenAnswer(call -> {
            List<?> inputs = call.getArgument(0);
            return new VoyageEmbeddingClient.BatchedEmbeddings(
                    inputs.stream().map(input -> ONE_POINT).toList(), 1);
        });

        Files.writeString(corpus.resolve("refund-policy.md"), """
                # Refund Policy

                Items may be returned within 30 days of delivery for a full refund.
                """);
    }

    // ---------------------------------------------------------------- (1) nothing lands in kb_chunks

    @Test
    void afterAFullRunTheRetrievalCorpusHoldsNoRowForTheCanaryPath() {
        IngestReport report = pipeline(probe).ingest();

        assertThat(report.isClean()).isTrue();
        assertThat(report.added())
                .as("the refund policy and the canary — both planned, both ingested")
                .isEqualTo(2);

        // THE CLAIM. Asserted against the table rather than against the repository, because the point
        // is what a future query can SEE, and a query does not go through the repository's opinion.
        assertThat(countChunksFor(CanaryDocument.PATH))
                .as("kb_chunks is the retrieval corpus; the canary is a measuring instrument and must "
                        + "not be a candidate answer in it")
                .isZero();

        // ...and the corollary that stops this from passing by the canary simply not being ingested.
        assertThat(probeRowCount()).isOne();
        assertThat(documents.findByPath(CanaryDocument.PATH))
                .as("the ledger row stays — same scan, same fingerprint, same plan, same transaction")
                .isPresent();
    }

    @Test
    void theProbeTableRefusesASecondRowAtTheSchemaLevel() {
        pipeline(probe).ingest();

        // The CHECK doing its job. This is what makes "there is exactly one probe" a fact the
        // application cannot violate rather than a convention every writer has to remember — the same
        // family of guarantee as vector(1024) rejecting a mis-sized embedding.
        assertThat(catchThrowable(() -> jdbc.update("""
                INSERT INTO canary_probe (id, embedding, embedding_model_id)
                VALUES (2, CAST(? AS vector), ?)
                """, VectorLiterals.toLiteral(ONE_POINT), DOCUMENT_MODEL)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("canary_probe_is_one_row");
    }

    // ---------------------------------------------------------------- (2) the shared transaction

    @Test
    void aFailedProbeUpsertAdvancesNeitherTheFingerprintNorTheProbe() {
        // Establish the healthy state first, so both "did not advance" assertions have a previous
        // value to be compared against. Asserting mere absence would be a weaker claim: it would pass
        // for a canary that was never ingested at all.
        pipeline(probe).ingest();

        KbDocument before = documents.findByPath(CanaryDocument.PATH).orElseThrow();
        String fingerprintBefore = before.getFingerprint();
        String storedModelBefore = probeModelId();

        // Move the canary's fingerprint by changing the embedding model id — the same lever a real
        // model migration pulls, and one that requires no edit to the frozen text.
        VoyageProperties newEra = new VoyageProperties("test-key", null, "voyage-4-large-v2",
                "voyage-4-lite", Duration.ofSeconds(2), Duration.ofSeconds(5), 2000, 300);

        CanaryProbeRepository failing = mock(CanaryProbeRepository.class);
        doThrow(new DataIntegrityViolationException("staged failure inside the canary transaction"))
                .when(failing).upsert(anyString(), anyString());

        IngestReport report = pipeline(failing, newEra).ingest();

        // The canary failed and was isolated; the policy document went through under the new era.
        assertThat(report.failed()).singleElement()
                .satisfies(failure -> assertThat(failure.path()).isEqualTo(CanaryDocument.PATH));
        assertThat(documents.findByPath("refund-policy.md").orElseThrow().getEmbeddingModelId())
                .as("failure isolation still holds — a real document in the same run committed")
                .isEqualTo("voyage-4-large-v2");

        // THE FATE-SHARING CLAIM, in both directions.
        assertThat(documents.findByPath(CanaryDocument.PATH).orElseThrow().getFingerprint())
                .as("the fingerprint is written in the same transaction as the probe, so a probe "
                        + "failure must roll it back — otherwise the canary is marked current while "
                        + "its vector is from the previous era, and every later run agrees and skips it")
                .isEqualTo(fingerprintBefore);
        assertThat(probeModelId())
                .as("and the probe row itself is untouched")
                .isEqualTo(storedModelBefore);
    }

    // ---------------------------------------------------------------- (3) unreachable by retrieval

    @Test
    void aRefundFlavouredQueryCannotReachTheCanaryEvenWhenTheirVectorsAreIdentical() {
        pipeline(probe).ingest();

        // The query vector IS the canary's vector, byte for byte — cosine distance zero. If the
        // canary were still a chunk it would tie for the top of this ranking on every refund
        // question, which is exactly the context-budget waste V4 removed.
        List<NearestChunk> nearest =
                chunks.findNearestWithDistance(VectorLiterals.toLiteral(ONE_POINT), 8);

        assertThat(nearest)
                .as("the ranking must be non-empty, or this test would pass on an empty corpus")
                .isNotEmpty();
        assertThat(nearest)
                .extracting(NearestChunk::getSourceDoc)
                .as("top-k on a refund-flavoured query returns policy documents and nothing else")
                .doesNotContain(CanaryDocument.PATH)
                .containsOnly("refund-policy.md");
    }

    // ---------------------------------------------------------------- fixtures

    private IngestionPipeline pipeline(CanaryProbeRepository probeRepository) {
        return pipeline(probeRepository, voyageProps);
    }

    private IngestionPipeline pipeline(CanaryProbeRepository probeRepository, VoyageProperties props) {
        // Constructed by hand rather than injected: two of these tests need a different collaborator
        // (a throwing probe, a different model era) and bean surgery on a live context to achieve that
        // would be more machinery and less clarity than calling the constructor.
        return new IngestionPipeline(chunker, voyage, chunks, documents, probeRepository, props,
                new EmbeddingProperties(DIM),
                new IngestionProperties(true, corpus.toString(), false), tx);
    }

    private int countChunksFor(String sourceDoc) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT count(*) FROM kb_chunks WHERE source_doc = ?", Integer.class, sourceDoc))
                .orElse(-1);
    }

    private int probeRowCount() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM canary_probe", Integer.class))
                .orElse(-1);
    }

    private String probeModelId() {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT embedding_model_id FROM canary_probe WHERE id = 1");
        return rows.isEmpty() ? null : (String) rows.getFirst().get("embedding_model_id");
    }

    private static float[] unit(int axis) {
        float[] vector = new float[DIM];
        vector[axis] = 1.0f;
        return vector;
    }
}
