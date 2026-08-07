package org.aura.aura.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * The single row of {@code canary_probe}: the stored side of the boot-time embedding-space check.
 *
 * <h2>Why this is not a {@link KbChunk}</h2>
 * It was one until V4, and the reason it stopped is a category error worth naming. {@code kb_chunks}
 * is the retrieval CORPUS — every row in it is a candidate answer to a customer question. The canary
 * is not a candidate answer; it is a measuring instrument that happens to have the same shape. Keeping
 * it in the corpus meant a refund query could match it and spend context budget on a duplicate of
 * {@code refund-policy.md} chunk 0. Separating by table rather than by a {@code WHERE} clause means
 * retrieval cannot see it even by accident, and no future query has to remember to exclude it.
 *
 * <h2>Why an entity at all, when nothing writes through it</h2>
 * Both writes go through native SQL on {@link CanaryProbeRepository} — an {@code ON CONFLICT} upsert
 * and a {@code <=>} distance. So this class exists almost entirely for
 * {@code spring.jpa.hibernate.ddl-auto=validate}: at every boot Hibernate compares this mapping,
 * including the vector column's declared dimension, against the live table and refuses to start on a
 * mismatch. That is the same drift guard {@link KbChunk} documents, extended to the one other table
 * in this schema that holds a vector — and it is what stops {@code canary_probe.embedding} and
 * {@code kb_chunks.embedding} from quietly becoming different widths, which would make the canary's
 * measurement incomparable to the ranking it is supposed to be guarding.
 */
@Entity
@Table(name = "canary_probe")
public class CanaryProbe {

    /**
     * The only id this table can hold — {@code CHECK (id = 1)} in V4 makes that a schema fact rather
     * than a convention. Declared here so the repository's lookup names the constraint instead of
     * spelling a bare {@code 1} that a reader has to go and verify.
     */
    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    /**
     * The stored {@code voyage-4-large}/{@code document} vector of {@code CanaryDocument}'s frozen
     * text. Both annotations are load-bearing and say different things, exactly as on {@link KbChunk}:
     * the JDBC type code picks pgvector's type, the array length supplies its dimension. Drop the
     * length and Hibernate renders an unbounded {@code vector}, which validates against nothing.
     *
     * <p>The width is read from {@link KbChunk#EMBEDDING_DIMENSION} rather than restated, because the
     * two columns being the SAME width is the property that makes the canary's distance comparable to
     * a retrieval distance. Two independent literals could drift; a shared constant cannot.
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = KbChunk.EMBEDDING_DIMENSION)
    @Column(nullable = false)
    private float[] embedding;

    /**
     * The model that produced {@link #embedding}, read off the ROW when a trip is diagnosed rather
     * than taken from configuration. Day 14's lane-flip drill is the whole argument for that
     * distinction: a guard whose message states what it INTENDED to do exonerates the actual cause and
     * sends the operator the wrong way.
     */
    @Column(name = "embedding_model_id", nullable = false)
    private String embeddingModelId;

    /**
     * Written by the DATABASE in both paths — a column default on insert, an explicit {@code now()}
     * in the upsert's update branch — hence {@code insertable/updatable = false}. One writer per
     * field; a JVM clock never gets a vote.
     */
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    /** Required by JPA; not for application use. Writes go through the repository's native upsert. */
    protected CanaryProbe() {
    }

    public Short getId() {
        return id;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
