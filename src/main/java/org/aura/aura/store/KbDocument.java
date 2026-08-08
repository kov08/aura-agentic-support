package org.aura.aura.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code kb_documents}: a source file the pipeline has ingested, and the fingerprint of
 * the version it ingested.
 *
 * <p>This is the state that makes ingestion incremental. Without it the only question the pipeline
 * can ask is "is the table empty?", which has exactly two answers and therefore exactly two
 * behaviours — skip everything, or re-embed everything. Day 13's loader had both and nothing in
 * between. A per-document fingerprint turns that into a per-document question, and the answer to a
 * per-document question is a plan.
 *
 * <h2>What is deliberately not here</h2>
 * No association to {@link KbChunk}. A {@code @OneToMany} would buy cascade semantics the database
 * already provides ({@code ON DELETE CASCADE}) and cost a lazy collection that can be dereferenced
 * from anywhere, which with {@code open-in-view=false} is a {@code LazyInitializationException}
 * waiting for its first caller. The pipeline addresses chunks by {@code document_id} through a bulk
 * query instead — one statement, no collection, no lifecycle.
 */
@Entity
@Table(name = "kb_documents")
public class KbDocument {

    /** APP-ASSIGNED — the chunks that reference it need the value before either row exists. */
    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String path;

    @Column(nullable = false)
    private String fingerprint;

    /**
     * Diagnostics, not logic. Both of the next two values are already folded into
     * {@link #fingerprint}; they are kept legible so an operator can see which era a document was
     * ingested under without reversing a hash. Nothing reads them to make a decision, and the day
     * something does, the fingerprint — not these — is the authority.
     */
    @Column(name = "embedding_model_id", nullable = false)
    private String embeddingModelId;

    @Column(name = "chunker_version", nullable = false)
    private String chunkerVersion;

    /**
     * Both timestamps are written by the DATABASE and never by the application — {@code created_at}
     * by a column default, {@code updated_at} by the {@code BEFORE UPDATE} trigger V3 installs. Hence
     * {@code insertable = false, updatable = false} on both: Hibernate is told, in the only language
     * it reads, that these columns are somebody else's.
     *
     * <p>The consequence to expect rather than discover: after a save, the in-memory instance still
     * holds whatever it was loaded with. Nothing in the pipeline reads these values back, so that
     * staleness is invisible; a future caller that needs a fresh one has to re-read the row.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    /** Required by JPA; not for application use. */
    protected KbDocument() {
    }

    public KbDocument(UUID id, String path, String fingerprint, String embeddingModelId,
                      String chunkerVersion) {
        this.id = id;
        this.path = path;
        this.fingerprint = fingerprint;
        this.embeddingModelId = embeddingModelId;
        this.chunkerVersion = chunkerVersion;
    }

    /**
     * Records that this document's chunks have been rebuilt under a new fingerprint.
     *
     * <p>A named mutator rather than three setters, because the three fields are ONE fact and must
     * move together: a fingerprint that says "current" beside a model id that says "the previous
     * era" is a row that lies in both directions at once. Setters would make that state reachable in
     * two statements; this makes it unreachable.
     *
     * <p>Call it LAST inside the swap transaction, after the chunks are written. The fingerprint is
     * the pipeline's only record that the work happened, so advancing it before the work commits is
     * how a failed document gets permanently skipped by every later run.
     */
    public void advanceTo(String fingerprint, String embeddingModelId, String chunkerVersion) {
        this.fingerprint = fingerprint;
        this.embeddingModelId = embeddingModelId;
        this.chunkerVersion = chunkerVersion;
    }

    public UUID getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public String getChunkerVersion() {
        return chunkerVersion;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
