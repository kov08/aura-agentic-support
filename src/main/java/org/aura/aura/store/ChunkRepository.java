package org.aura.aura.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The vector store's read/write surface. Everything ordinary — {@code save}, {@code count},
 * {@code deleteAllInBatch} — comes free from {@link JpaRepository}; the one method that matters is the
 * similarity search, and it is hand-written SQL for reasons worth stating.
 */
public interface ChunkRepository extends JpaRepository<KbChunk, UUID> {

    /**
     * The hot path: the {@code k} chunks nearest to {@code queryVector}, closest first.
     *
     * <p>This one query replaces the entire Day 12 demo loop — score every chunk in memory, sort,
     * truncate. Same three steps, moved to where the data already is. The win is not that the database
     * is faster at arithmetic; it is that the corpus no longer has to fit in the JVM and be re-embedded
     * on every restart.
     *
     * <h2>Why native SQL, and why the operator is written out</h2>
     * {@code <=>} is pgvector's cosine-distance operator, and it is spelled explicitly here rather than
     * hidden behind a JPQL function or a Hibernate {@code cosine_distance(...)} wrapper. The choice of
     * operator IS the retrieval semantics — {@code <=>} cosine, {@code <->} L2, {@code <#>} negative
     * inner product — and swapping one for another changes every ranking while breaking nothing that
     * would fail a test. It also fixes which vector index could ever serve this query, since an
     * approximate index is built for one operator. A decision with that blast radius belongs in plain
     * sight in the query text, not behind an abstraction that makes it look interchangeable.
     *
     * <p>Note the ordering direction: {@code <=>} is a DISTANCE, so ascending is "most similar first"
     * — the opposite sort from the Day 12 demo's cosine SIMILARITY. The two quantities are related by
     * {@code distance = 1 - similarity}, and getting the direction wrong returns the least relevant
     * chunks in a confident-looking ranked list.
     *
     * @param queryVector the query embedding in pgvector text form; build it with
     *                    {@link org.aura.aura.util.VectorLiterals#toLiteral}. It arrives as a string
     *                    because a native query parameter carries no entity type to bind through,
     *                    which is exactly what the {@code CAST} on the far side repairs.
     * @param k           how many chunks to return
     */
    @Query(value = """
            SELECT * FROM kb_chunks
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :k
            """, nativeQuery = true)
    List<KbChunk> findNearest(@Param("queryVector") String queryVector, @Param("k") int k);

    /**
     * Day 14's hot path: the same search as {@link #findNearest}, but projecting the DISTANCE that
     * produced each row's rank, and not projecting the embedding at all.
     *
     * <p>This is the method the live request path uses; {@code findNearest} above survives for the
     * demo and the schema tests, which want whole entities. See {@link NearestChunk} for why the
     * distance is projected rather than recomputed (Decision 3A) and why the vector is left behind.
     *
     * <h2>The operator appears twice, deliberately</h2>
     * Once in the SELECT list and once in the ORDER BY. Postgres evaluates the ordering expression
     * against the same index-or-scan path either way, so this is not two scans — but it IS one place
     * where an editor could change one occurrence and not the other, and the result would be a ranked
     * list annotated with distances from a different metric: perfectly ordered rows carrying numbers
     * that do not explain the order. Both spellings are identical on purpose; keep them that way.
     *
     * <p>Every alias is DOUBLE-QUOTED because Postgres folds unquoted identifiers to lower case, and
     * Spring Data matches the projection's getters against the JDBC column label verbatim —
     * {@code AS sourceDoc} arrives as {@code sourcedoc} and silently binds nothing.
     */
    @Query(value = """
            SELECT id                                        AS "chunkId",
                   source_doc                                AS "sourceDoc",
                   chunk_index                               AS "chunkIndex",
                   breadcrumb                                AS "breadcrumb",
                   content                                   AS "content",
                   token_count                               AS "tokenCount",
                   embedding <=> CAST(:queryVector AS vector) AS "distance"
            FROM kb_chunks
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT :k
            """, nativeQuery = true)
    List<NearestChunk> findNearestWithDistance(@Param("queryVector") String queryVector,
                                               @Param("k") int k);

    /**
     * Removes every chunk belonging to one document — the DELETE half of Day 15's per-document swap.
     *
     * <h2>Why a bulk query and not {@code deleteByDocumentId} derived by Spring Data</h2>
     * A derived {@code deleteBy...} SELECTs the matching entities, materialises them, and issues one
     * DELETE per row so entity lifecycle callbacks can fire. There are no callbacks on
     * {@link KbChunk}, so all that buys is N+1 statements and a persistence context full of rows
     * about to stop existing. This is one statement.
     *
     * <h2>The ordering property the pipeline depends on</h2>
     * {@code @Modifying} queries execute when the method is CALLED, not at flush. That is what makes
     * "delete the old chunks, then insert the new ones" true on the wire and not merely true in the
     * source: the inserts queued afterwards are flushed at commit, strictly later. Without it,
     * Hibernate's own flush ordering puts inserts before deletes and the swap collides with
     * {@code UNIQUE (source_doc, chunk_index)} on every re-ingestion of a changed document.
     *
     * <p>It also bypasses the persistence context entirely — rows deleted here stay cached if they
     * were already loaded. Nothing in the pipeline loads chunks before deleting them, so there is no
     * stale instance to be confused by; a future caller that does needs {@code clearAutomatically}.
     *
     * @return how many rows were removed — the number the swap logs, so a re-ingest that silently
     *         matched nothing is visible rather than inferred
     */
    @Modifying
    @Query("DELETE FROM KbChunk c WHERE c.documentId = :documentId")
    int deleteByDocumentId(@Param("documentId") UUID documentId);

    /**
     * The canary row, addressed by the identity the schema already declares unique
     * ({@code source_doc, chunk_index}) rather than by its uuid.
     *
     * <p>The uuid would be the obvious key and is the wrong one: the ingestion pipeline assigns
     * {@code UUID.randomUUID()} to every chunk it writes, so it changes on every rebuild. A canary
     * pinned to a value that changes whenever the corpus is re-ingested is a canary that fails for
     * the one reason it is not meant to detect.
     */
    Optional<KbChunk> findBySourceDocAndChunkIndex(String sourceDoc, int chunkIndex);

    /**
     * The cosine distance between one stored chunk's embedding and a supplied query vector —
     * measured by Postgres, with the same operator the ranked search uses.
     *
     * <p>The canary could have computed this in the JVM with {@link org.aura.aura.util.VectorMath}
     * and one fewer round-trip. It does not, and that is the point of the guard: the number the
     * canary compares against a pre-registered band has to be measured on the SAME arithmetic every
     * customer query is ranked by, or the band is calibrated against something no request rides.
     *
     * @return empty when no row carries that identity — the caller decides whether that is a
     *         not-yet-ingested corpus (benign) or a drifted canary (not)
     */
    @Query(value = """
            SELECT embedding <=> CAST(:queryVector AS vector)
            FROM kb_chunks
            WHERE source_doc = :sourceDoc AND chunk_index = :chunkIndex
            """, nativeQuery = true)
    Optional<Double> distanceFrom(@Param("sourceDoc") String sourceDoc,
                                  @Param("chunkIndex") int chunkIndex,
                                  @Param("queryVector") String queryVector);
}
