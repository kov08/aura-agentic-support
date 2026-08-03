package org.aura.aura.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
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
}
