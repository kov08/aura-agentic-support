package org.aura.aura.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * The canary probe's two operations: write the vector, measure against it.
 *
 * <p>Both are native SQL, and neither could be anything else. The write needs Postgres's
 * {@code ON CONFLICT} to be an upsert in one statement; the read needs pgvector's {@code <=>}
 * operator, which no JPQL dialect exposes. The entity exists for schema validation, not for
 * persistence traffic — see {@link CanaryProbe}.
 */
public interface CanaryProbeRepository extends JpaRepository<CanaryProbe, Short> {

    /**
     * Writes the probe vector, creating the row on the first run and replacing it thereafter.
     *
     * <h2>Why an upsert and not delete-then-insert</h2>
     * The pipeline's per-document swap wipes chunks and rewrites them, and copying that shape here
     * would open a window — however short, inside one transaction — where the probe does not exist.
     * There is exactly one row and it is addressed by a constant, so the conflict target is known
     * ahead of time and the whole update is one statement with no intermediate state at all.
     *
     * <h2>Ordering, and why it matters here too</h2>
     * {@code @Modifying} means this executes when it is CALLED, not at flush — so it lands inside the
     * caller's transaction at the point the caller wrote it, alongside the ledger update it has to
     * be atomic with. That is what makes "a failed canary advances neither the fingerprint nor the
     * probe" true rather than hoped for.
     *
     * @param embedding        the vector in pgvector text form; build it with
     *                         {@link org.aura.aura.util.VectorLiterals#toLiteral}. A string because a
     *                         native parameter carries no entity type to bind through, which is what
     *                         the {@code CAST} on the far side repairs
     * @param embeddingModelId the DOCUMENT-lane model that produced it, stamped from the same
     *                         configuration that routed the embedding call
     * @return rows affected — always 1, and returned so a caller that ever gets 0 finds out
     */
    @Modifying
    @Query(value = """
            INSERT INTO canary_probe (id, embedding, embedding_model_id, updated_at)
            VALUES (1, CAST(:embedding AS vector), :embeddingModelId, now())
            ON CONFLICT (id) DO UPDATE
               SET embedding          = EXCLUDED.embedding,
                   embedding_model_id = EXCLUDED.embedding_model_id,
                   updated_at         = now()
            """, nativeQuery = true)
    int upsert(@Param("embedding") String embedding,
               @Param("embeddingModelId") String embeddingModelId);

    /**
     * The cosine distance between the stored probe vector and a supplied query vector, measured by
     * POSTGRES with the same operator the ranked search uses.
     *
     * <p>The check could compute this in the JVM with {@code VectorMath} and one fewer round-trip. It
     * does not, and that is the point of the guard: the number compared against a pre-registered band
     * has to be produced by the SAME arithmetic every customer query is ranked by, or the band is
     * calibrated against something no request rides.
     *
     * @return empty when the probe row does not exist — the caller decides whether that is a
     *         not-yet-ingested store (benign, and the normal state on the boot that will populate it)
     *         or something worse
     */
    @Query(value = """
            SELECT embedding <=> CAST(:queryVector AS vector)
            FROM canary_probe
            WHERE id = 1
            """, nativeQuery = true)
    Optional<Double> distanceFrom(@Param("queryVector") String queryVector);

    /**
     * The probe row, addressed by the constant the schema's {@code CHECK} pins.
     *
     * <p>A named method rather than {@code findById(1)} at each call site: the literal is a schema
     * invariant, not a caller's choice, and spelling it once keeps that visible.
     */
    default Optional<CanaryProbe> findProbe() {
        return findById(CanaryProbe.SINGLETON_ID);
    }
}
