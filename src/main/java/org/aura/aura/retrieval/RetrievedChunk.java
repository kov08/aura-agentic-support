package org.aura.aura.retrieval;

import org.aura.aura.store.NearestChunk;

import java.util.UUID;

/**
 * A search hit, in the shape the packer and the assembler need: the chunk's text and citation
 * handles, its stored token count, its document identity, and the distance that ranked it.
 *
 * <p>The immutable in-memory twin of {@link NearestChunk}, which is a Spring Data projection over a
 * native query. Converting once at the repository boundary keeps everything downstream — packing,
 * dedup, canonical assembly — as pure logic over records, testable with hand-built instances and no
 * database anywhere. That is the same split {@code Chunk} and {@code KbChunk} already make.
 *
 * @param tokenCount the ingestion-time APPROXIMATION (~4 chars/token) stored on the row. Nothing
 *                   tokenizes at request time: the budget is spent in integer arithmetic on numbers
 *                   the database already holds, which is what keeps packing free.
 */
public record RetrievedChunk(UUID chunkId,
                             String sourceDoc,
                             int chunkIndex,
                             String breadcrumb,
                             String content,
                             int tokenCount,
                             double distance) {

    public static RetrievedChunk from(NearestChunk row) {
        return new RetrievedChunk(
                row.getChunkId(), row.getSourceDoc(), row.getChunkIndex(),
                row.getBreadcrumb(), row.getContent(), row.getTokenCount(), row.getDistance());
    }

    /**
     * Adjacency, decided by IDENTITY: same document, chunk indexes one apart.
     *
     * <p>Never by comparing text. Two neighbouring chunks genuinely share bytes — the chunker
     * prepends a 300-character overlap to every sub-chunk, so the tail of chunk <i>i</i> is the head
     * of chunk <i>i+1</i> by construction — and a text-similarity test for "these overlap" would be
     * re-deriving at request time a fact the schema already records exactly. It would also be a
     * threshold, which means a tuning knob, which means a silent behaviour change the first time a
     * document's prose gets repetitive. {@code (source_doc, chunk_index)} is already the corpus's
     * declared unique identity; adjacency is arithmetic on it.
     *
     * <p>Strictly {@code |Δindex| == 1}. Chunks two apart share nothing under the current overlap, so
     * widening this would start discarding genuinely distinct passages that happen to be near each
     * other in one document — which is the opposite of the goal.
     */
    public boolean isAdjacentTo(RetrievedChunk other) {
        return sourceDoc.equals(other.sourceDoc) && Math.abs(chunkIndex - other.chunkIndex) == 1;
    }

    public SourceRef toSourceRef() {
        return new SourceRef(chunkId, breadcrumb, distance);
    }
}
