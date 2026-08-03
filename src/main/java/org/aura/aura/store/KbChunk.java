package org.aura.aura.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The persistent form of a {@link org.aura.aura.domain.Chunk} plus its embedding — one row of
 * {@code kb_chunks}.
 *
 * <p>Deliberately a SEPARATE type from {@code Chunk} rather than the same record with annotations on
 * it. {@code Chunk} is the chunker's output: pure, immutable, storage-unaware, and unit-tested with no
 * Spring context at all. This is a mutable, identity-bearing JPA entity whose shape is dictated by a
 * table. Fusing them would drag JPA into the chunker's tests and let a schema change ripple into a
 * pure-logic type; keeping them apart costs one small mapping in {@code KbCorpusLoader} and buys that
 * separation outright.
 *
 * <h2>The vector column</h2>
 * {@code @JdbcTypeCode(SqlTypes.VECTOR)} plus {@code @Array(length = ...)} is what makes
 * {@code float[]} map to pgvector's {@code vector(1024)} rather than to a bytea or a Postgres
 * {@code real[]}. Both annotations are load-bearing and they say different things: the JDBC type code
 * picks pgvector's type, the array length supplies its dimension. Drop the length and Hibernate
 * renders an unbounded {@code vector}, which validates against nothing and stores anything.
 *
 * <p>Because {@code spring.jpa.hibernate.ddl-auto=validate} compares this mapping against the live
 * column at every boot, {@link #EMBEDDING_DIMENSION} and the {@code vector(1024)} in
 * {@code V2__create_kb_chunks.sql} cannot silently diverge. {@code EmbeddingDimensionCheck} closes the
 * remaining edge of the triangle by comparing the live column against {@code aura.embedding.dimension}
 * — between the two, all three copies of "1024" are pinned to each other at startup.
 */
@Entity
@Table(name = "kb_chunks")
public class KbChunk {

    /**
     * The vector width, duplicated from {@code aura.embedding.dimension} because an annotation
     * argument must be a compile-time constant and cannot read configuration. That duplication is not
     * hidden — it is asserted against the database at every boot (see the class javadoc), which is the
     * only honest thing to do with a constant that cannot be derived.
     */
    public static final int EMBEDDING_DIMENSION = 1024;

    /**
     * APP-ASSIGNED, so there is no generation strategy and no database round-trip to learn the key.
     *
     * <p>One JPA consequence worth naming rather than discovering: with an assigned id,
     * {@code JpaRepository.save} sees a non-null id, treats the instance as detached, and issues a
     * {@code merge} — a SELECT before the INSERT. At corpus scale (tens of chunks, ingested once)
     * that is irrelevant; at Day 15's scale it is the reason the real ingestion path will use an
     * explicit batched upsert instead of {@code saveAll}.
     */
    @Id
    private UUID id;

    @Column(name = "source_doc", nullable = false)
    private String sourceDoc;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false)
    private String breadcrumb;

    @Column(nullable = false)
    private String content;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = EMBEDDING_DIMENSION)
    @Column(nullable = false)
    private float[] embedding;

    /**
     * The model that produced {@link #embedding}. Stored per ROW, not per deployment, because a corpus
     * can legitimately be mid-migration: half re-embedded with a new model, half not. A single global
     * "current model" setting cannot represent that state, and the Day 12 lab measured what it costs
     * to be unable to see it — cross-era vectors rank at random while every layer reports success.
     */
    @Column(name = "embedding_model", nullable = false)
    private String embeddingModel;

    /**
     * Written by the DATABASE (`DEFAULT now()`), never by the application — hence
     * {@code insertable = false}. One writer per field: two clocks disagreeing about when a chunk was
     * ingested is a debugging problem nobody should have to have. The field is therefore null on a
     * freshly constructed instance and populated on read, which is the correct reflection of who owns
     * the value.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Required by JPA; not for application use. */
    protected KbChunk() {
    }

    public KbChunk(UUID id, String sourceDoc, int chunkIndex, String breadcrumb, String content,
                   int tokenCount, float[] embedding, String embeddingModel) {
        this.id = id;
        this.sourceDoc = sourceDoc;
        this.chunkIndex = chunkIndex;
        this.breadcrumb = breadcrumb;
        this.content = content;
        this.tokenCount = tokenCount;
        this.embedding = embedding;
        this.embeddingModel = embeddingModel;
    }

    public UUID getId() {
        return id;
    }

    public String getSourceDoc() {
        return sourceDoc;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getBreadcrumb() {
        return breadcrumb;
    }

    public String getContent() {
        return content;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
