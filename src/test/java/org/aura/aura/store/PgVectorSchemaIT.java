package org.aura.aura.store;

import org.aura.aura.config.EmbeddingProperties;
import org.aura.aura.util.VectorLiterals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Day 13: the vector store, against a REAL Postgres with a real pgvector extension and the real
 * migrations. Nothing here is mocked and nothing here touches the network beyond Docker — Voyage is
 * never called, because every vector in this class is hand-built and deterministic.
 *
 * <p>That is deliberate. Retrieval QUALITY is a property of the embedding model and belongs in the
 * billable manual demo; retrieval MECHANICS — does the schema exist, does the operator order rows the
 * way the operator says it does, does the column refuse a vector of the wrong width — are properties
 * of Postgres and this schema, and they should be provable for free, offline, on every build.
 */
// "test" supplies the non-blank Voyage/Anthropic keys and suppresses ConversationRunner; "it" adds the
// aggressive MockWebServer-era timeouts, harmless here and kept for consistency with the other ITs.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // Opting BACK IN to the database. application-test.yml excludes DataSourceAutoConfiguration so
        // the DB-less majority of the suite needs no Docker; an explicit empty value at the
        // highest-precedence property source replaces that list rather than merging with it. This one
        // line is the whole "the database is opt-in under test" mechanism, seen from the opting-in end.
        properties = "spring.autoconfigure.exclude=")
@ActiveProfiles({"test", "it"})
@Testcontainers
class PgVectorSchemaIT {

    /**
     * pgvector's image, declared as a substitute for the plain {@code postgres} one.
     *
     * <p>{@code asCompatibleSubstituteFor} is required, not decorative: {@link PostgreSQLContainer}
     * refuses any image name it does not recognise as Postgres, and {@code pgvector/pgvector} is a
     * different repository. The annotation is the explicit "I know this is Postgres underneath"
     * that unlocks the container's Postgres-aware wait strategy and JDBC url building.
     */
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired ChunkRepository repository;
    @Autowired DocumentRepository documents;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmbeddingProperties embeddingProps;

    @Value("${spring.jpa.hibernate.ddl-auto}") String ddlAuto;

    private static final int DIM = KbChunk.EMBEDDING_DIMENSION;

    @BeforeEach
    void clean() {
        repository.deleteAllInBatch();
        // Parents second: ON DELETE CASCADE would take the chunks with them anyway, but wiping the
        // children explicitly first keeps this method's effect independent of the constraint it is
        // partly here to test.
        documents.deleteAllInBatch();
    }

    // ---------------------------------------------------------------- the migrations themselves

    @Test
    void flywayAppliedBothMigrationsSuccessfully() {
        // Asserting against flyway_schema_history rather than against the table's existence: the table
        // existing proves *something* created it, this proves FLYWAY did, in order, and recorded it.
        // The difference matters the day someone "fixes" a boot failure by creating a table by hand.
        List<Map<String, Object>> applied = jdbc.queryForList("""
                SELECT version, description, success
                FROM flyway_schema_history
                WHERE version IS NOT NULL
                ORDER BY installed_rank
                """);

        assertThat(applied)
                .as("V1 (extension), V2 (kb_chunks) and V3 (kb_documents + the FK) must all be "
                        + "recorded as applied")
                .hasSize(3);
        assertThat(applied.get(0)).containsEntry("version", "1").containsEntry("success", true);
        assertThat(applied.get(1)).containsEntry("version", "2").containsEntry("success", true);
        assertThat(applied.get(2)).containsEntry("version", "3").containsEntry("success", true);

        // V1's actual effect, checked directly — a migration recorded as successful and an extension
        // that is actually enabled are two different claims.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
                .as("V1 must have enabled the pgvector extension in THIS database")
                .isEqualTo(1);
    }

    @Test
    void hibernateValidatedTheSchemaItDidNotWrite() {
        // The assertion is really the fact that this context started at all: with ddl-auto=validate,
        // Hibernate compares every mapping in KbChunk — including the vector column's dimension —
        // against the live table during EntityManagerFactory creation, and a mismatch fails the
        // context before any test method runs. Naming the property here keeps that implicit proof from
        // being silently disarmed by someone switching it to `none` to make a boot error go away.
        assertThat(ddlAuto)
                .as("the schema-drift guard is ddl-auto=validate; `none` or `update` disables it")
                .isEqualTo("validate");
    }

    // ---------------------------------------------------------------- the dimension contract

    @Test
    void declaredDimensionQueryReturnsTheVectorWidthEmpirically() {
        // The empirical half of EmbeddingDimensionCheck: pgvector is DOCUMENTED to store a vector's
        // dimension raw in atttypmod (no VARHDRSZ offset), and this asserts it against a real column
        // rather than trusting that. If a future pgvector changes the encoding, this fails here —
        // loudly, in a test named after the claim — instead of the startup check quietly comparing the
        // wrong number and passing.
        Map<String, Object> row = jdbc.queryForMap(
                EmbeddingDimensionCheck.DECLARED_DIMENSION_QUERY, "kb_chunks", "embedding");

        assertThat(row.get("typmod")).isEqualTo(DIM);
        assertThat(row.get("declared_type")).isEqualTo("vector(" + DIM + ")");
        assertThat(embeddingProps.dimension())
                .as("aura.embedding.dimension must equal the width the schema declares")
                .isEqualTo(DIM);
    }

    @Test
    void aMismatchedConfiguredDimensionRefusesToBoot() {
        // The other half of the check, and the half that is easy to ship broken: it is not enough that
        // the assertion passes when things agree, it has to FAIL when they do not — otherwise it is a
        // log line wearing a guard's uniform.
        //
        // A second, minimal context is built against the same container so the failure can be observed
        // as a startup failure rather than as a method call that threw. Note what this also
        // demonstrates: the schema is already at v2, so migrate() applies nothing — and AFTER_MIGRATE
        // still fires. The check guards every boot, not only the one that wrote the schema.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withBean(EmbeddingDimensionCheck.class, new EmbeddingProperties(768))
                .withPropertyValues(
                        "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + POSTGRES.getUsername(),
                        "spring.datasource.password=" + POSTGRES.getPassword())
                .run(context -> {
                    assertThat(context)
                            .as("a configured dimension that disagrees with the live column must not boot")
                            .hasFailed();
                    assertThat(context.getStartupFailure())
                            // Both numbers in the message: "mismatch" alone sends the reader looking,
                            // "768 vs vector(1024)" tells them which one to change.
                            .hasStackTraceContaining("aura.embedding.dimension is 768")
                            .hasStackTraceContaining("vector(1024)");
                });
    }

    @Test
    void postgresRejectsAVectorOfTheWrongDimension() {
        // THE contract enforcing itself. This is the single most valuable property of putting the
        // corpus in pgvector rather than in a blob column or a float array: a 512-dimension vector is
        // not stored-and-mis-ranked, it is refused. Everything else here could be reimplemented in
        // application code; this cannot, because application code is exactly what would have the bug.
        //
        // Driven through raw JDBC rather than the entity, on purpose: @Array(length = 1024) means
        // Hibernate would object first, and then the test would prove that Hibernate is configured
        // correctly rather than that Postgres is enforcing anything.
        float[] tooShort = new float[512];
        tooShort[0] = 1.0f;
        UUID documentId = KbFixtures.documentId(documents, "wrong-dimension.md");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO kb_chunks
                    (id, document_id, source_doc, chunk_index, breadcrumb, content, token_count,
                     embedding, embedding_model)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?)
                """,
                UUID.randomUUID(), documentId, "wrong-dimension.md", 0, "Wrong > Dimension",
                "a vector of the wrong width", 7, VectorLiterals.toLiteral(tooShort), "test-model"))
                .as("a 512-dimension vector must not fit a vector(1024) column")
                .hasMessageContaining("expected " + DIM + " dimensions");

        assertThat(repository.count())
                .as("the rejected row must not have landed")
                .isZero();
    }

    // ---------------------------------------------------------------- the hot path

    @Test
    void findNearestReturnsChunksInAscendingDistanceOrder() {
        // Three vectors chosen so the expected ranking is arithmetic, not a model's opinion:
        //   exactMatch  — the query itself                       → cosine distance 0
        //   halfway     — 45° from the query                     → 1 - cos(45°) ≈ 0.2929
        //   orthogonal  — perpendicular to the query             → 1
        // No embedding model is involved, so this test can never fail because a provider retrained
        // something. It is testing the OPERATOR and the ORDER, which are ours to pin.
        float[] exactMatch = unit(0);
        float[] orthogonal = unit(1);
        float[] halfway = new float[DIM];
        halfway[0] = 1.0f;
        halfway[1] = 1.0f;   // 45° between axis 0 and axis 1; magnitude is irrelevant to cosine

        repository.saveAll(List.of(
                chunk("orthogonal.md", 0, "Orthogonal", orthogonal),
                chunk("exact.md", 0, "Exact", exactMatch),
                chunk("halfway.md", 0, "Halfway", halfway)));

        List<KbChunk> nearest = repository.findNearest(VectorLiterals.toLiteral(exactMatch), 3);

        assertThat(nearest)
                .as("<=> is a DISTANCE, so ascending order is most-similar-first")
                .extracting(KbChunk::getBreadcrumb)
                .containsExactly("Exact", "Halfway", "Orthogonal");
    }

    @Test
    void findNearestHonoursK() {
        repository.saveAll(List.of(
                chunk("a.md", 0, "A", unit(0)),
                chunk("b.md", 0, "B", unit(1)),
                chunk("c.md", 0, "C", unit(2))));

        assertThat(repository.findNearest(VectorLiterals.toLiteral(unit(0)), 2))
                .hasSize(2)
                .extracting(KbChunk::getBreadcrumb)
                .containsExactly("A", "B");
    }

    @Test
    void aStoredChunkRoundTripsWithItsVectorAndProvenance() {
        float[] embedding = unit(7);
        KbChunk saved = repository.saveAndFlush(
                chunk("refund-policy.md", 3, "Refund Policy > International Orders", embedding));

        KbChunk found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getEmbedding())
                .as("the vector must survive the round-trip element-for-element")
                .containsExactly(embedding);
        assertThat(found.getSourceDoc()).isEqualTo("refund-policy.md");
        assertThat(found.getChunkIndex()).isEqualTo(3);
        assertThat(found.getEmbeddingModel())
                .as("provenance is stored per row — the Day 12 lab's missing signal")
                .isEqualTo("voyage-4-large");
        assertThat(found.getCreatedAt())
                .as("created_at is written by the database's clock, not the application's")
                .isNotNull();
    }

    // ---------------------------------------------------------------- the document ledger (V3)

    @Test
    void deletingADocumentTakesItsChunksWithIt() {
        // The guardrail half of Day 15's belt-and-braces. IngestionPipeline deletes a document's
        // chunks explicitly before deleting the document — that is the belt, and it lives in Java
        // where a refactor can lose it. This is the braces, and it lives in the schema where a
        // refactor cannot: after ON DELETE CASCADE, an orphaned chunk is not merely unlikely, it is
        // unrepresentable.
        repository.saveAll(List.of(
                chunk("doomed.md", 0, "Doomed > One", unit(0)),
                chunk("doomed.md", 1, "Doomed > Two", unit(1)),
                chunk("survivor.md", 0, "Survivor", unit(2))));
        UUID doomedId = KbFixtures.documentId(documents, "doomed.md");

        documents.deleteById(doomedId);
        documents.flush();

        assertThat(repository.findAll())
                .as("both chunks of the deleted document must be gone, and only those")
                .extracting(KbChunk::getSourceDoc)
                .containsExactly("survivor.md");
    }

    @Test
    void aChunkCannotBeInsertedWithoutAParentDocument() {
        // The other half of the same constraint, and the one V3's migration existed to establish:
        // the Day 13 rows were chunks with no document accounting for them, and after this migration
        // that state is not reachable.
        float[] vector = unit(0);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO kb_chunks
                    (id, document_id, source_doc, chunk_index, breadcrumb, content, token_count,
                     embedding, embedding_model)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?)
                """,
                UUID.randomUUID(), UUID.randomUUID(), "orphan.md", 0, "Orphan", "no parent", 3,
                VectorLiterals.toLiteral(vector), "voyage-4-large"))
                .as("a document_id pointing at no kb_documents row must be refused")
                .hasMessageContaining("kb_chunks_document_id_fkey");
    }

    @Test
    void aDocumentPathIsUnique() {
        // The path IS the document's identity to the pipeline: the plan is a diff of two maps keyed
        // on it, and a duplicate makes that diff ambiguous in a way no code path handles.
        KbFixtures.documentId(documents, "refund-policy.md");

        assertThatThrownBy(() -> documents.saveAndFlush(new KbDocument(
                UUID.randomUUID(), "refund-policy.md", "another-fingerprint",
                "voyage-4-large", "v1")))
                .hasMessageContaining("kb_documents_path_key");
    }

    @Test
    void theDatabaseWritesBothDocumentTimestampsAndTouchesUpdatedAtOnEveryUpdate() {
        // One writer per field, and here that writer is Postgres for both. A column DEFAULT covers
        // the insert; only the BEFORE UPDATE trigger covers the update, which is the half that is
        // easy to ship missing — nothing fails without it, the value just silently stops moving.
        UUID id = KbFixtures.documentId(documents, "timestamped.md");
        KbDocument inserted = documents.findById(id).orElseThrow();

        assertThat(inserted.getCreatedAt()).isNotNull();
        assertThat(inserted.getUpdatedAt()).isNotNull();

        inserted.advanceTo("a-new-fingerprint", "voyage-4-large", "v2");
        documents.saveAndFlush(inserted);

        // Read back through JDBC, not through the repository. The in-memory instance is stale by
        // design (both timestamp columns are insertable=false, updatable=false, so Hibernate never
        // refreshes them), and a findById would be served from the same persistence context — which
        // would pass whether or not the trigger exists.
        assertThat(jdbc.queryForObject(
                "SELECT updated_at >= created_at AND fingerprint = 'a-new-fingerprint' "
                        + "FROM kb_documents WHERE id = ?", Boolean.class, id))
                .as("the BEFORE UPDATE trigger must move updated_at when the row changes")
                .isTrue();
    }

    @Test
    void twoChunksAtTheSamePositionInTheSameDocumentCollide() {
        // The constraint that makes a re-ingestion a conflict instead of a silent duplicate. Without
        // it, running the loader twice doubles the corpus and every query returns the same passage
        // twice at the top — a retrieval defect that looks like a ranking defect.
        repository.saveAndFlush(chunk("refund-policy.md", 0, "First", unit(0)));

        assertThatThrownBy(() -> repository.saveAndFlush(chunk("refund-policy.md", 0, "Second", unit(1))))
                .as("UNIQUE (source_doc, chunk_index) is what Day 15's upsert will target")
                .hasMessageContaining("kb_chunks_doc_position_unique");
    }

    // ---------------------------------------------------------------- fixtures

    /** A unit basis vector — 1 at {@code axis}, 0 everywhere else. Deterministic and trivially ranked. */
    private static float[] unit(int axis) {
        float[] vector = new float[DIM];
        vector[axis] = 1.0f;
        return vector;
    }

    private KbChunk chunk(String sourceDoc, int index, String breadcrumb, float[] embedding) {
        return new KbChunk(UUID.randomUUID(), KbFixtures.documentId(documents, sourceDoc), sourceDoc,
                index, breadcrumb, "content of " + breadcrumb, 8, embedding, "voyage-4-large");
    }
}
