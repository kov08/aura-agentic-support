package org.aura.aura.store;

import java.util.UUID;

/**
 * One row of a similarity search: the chunk's retrievable fields plus <b>the distance that produced
 * its rank</b>.
 *
 * <h2>Decision 3A — distance as data</h2>
 * The distance is PROJECTED by the SQL that did the ordering, not recomputed in the JVM afterwards.
 * Those are two different numbers whenever they disagree, and the one that matters is the one the
 * database sorted by: a locally recomputed score can rank rows differently from the list they arrived
 * in, and then the citation says 0.31 while the row above it says 0.34 and nobody can explain the
 * ordering. Projecting it makes the rank and the reported distance the SAME fact, by construction.
 *
 * <p>{@link org.aura.aura.util.VectorMath} survives only as a unit-test cross-check on that
 * definition — it is no longer on any request path.
 *
 * <h2>Why an interface and not the entity</h2>
 * Deliberately NOT a {@code KbChunk}. Two reasons, both about the hot path. An entity carries the
 * {@code embedding} column, and dragging 1024 floats per row across the wire and into the persistence
 * context — eight times per ticket — buys nothing, because nothing downstream of retrieval looks at
 * the vector again. And a distance is not a property of a chunk; it is a property of a chunk RELATIVE
 * TO ONE QUERY, so hanging it on the entity would be modelling a per-request measurement as durable
 * state.
 *
 * <p>Spring Data materialises this from the aliases in the native query, which is why every alias
 * there is double-quoted: Postgres folds unquoted identifiers to lower case, and {@code sourcedoc}
 * does not match {@code getSourceDoc}.
 */
public interface NearestChunk {

    UUID getChunkId();

    String getSourceDoc();

    int getChunkIndex();

    String getBreadcrumb();

    String getContent();

    int getTokenCount();

    /** pgvector's {@code <=>} — cosine DISTANCE, so smaller is nearer. */
    double getDistance();
}
