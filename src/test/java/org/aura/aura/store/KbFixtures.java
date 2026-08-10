package org.aura.aura.store;

import org.aura.aura.chunker.DocumentChunker;

import java.util.UUID;

/**
 * Shared setup for the database-backed tests that write {@code kb_chunks} directly.
 *
 * <p>Day 15's V3 migration made {@code kb_chunks.document_id} a NOT NULL foreign key, so a chunk can
 * no longer be inserted on its own — every test that seeds the corpus by hand now needs a parent row
 * first. This exists so that requirement is written once: two tests each rolling their own parent
 * would be two places to update the day {@code kb_documents} grows a column, and the second one
 * would be found by a failing FK rather than by a reader.
 */
public final class KbFixtures {

    private KbFixtures() {
    }

    /**
     * Finds or creates the {@code kb_documents} row for {@code path} and returns its id.
     *
     * <p>{@code saveAndFlush}, not {@code save}: the caller's next statement inserts chunks that
     * reference this id, and a parent still sitting in the persistence context is not a parent
     * Postgres can see when it checks the constraint.
     *
     * <p>The fingerprint is a fixture value and is deliberately NOT a real
     * {@code DocumentFingerprinter} hash. These tests seed vectors by hand — unit basis vectors,
     * not embeddings — so there is no content the fingerprint could honestly describe, and computing
     * one would dress a placeholder up as provenance.
     */
    public static UUID documentId(DocumentRepository documents, String path) {
        return documents.findByPath(path)
                .orElseGet(() -> documents.saveAndFlush(new KbDocument(
                        UUID.randomUUID(), path, "fixture-fingerprint-" + path,
                        "voyage-4-large", DocumentChunker.CHUNKER_VERSION)))
                .getId();
    }

    /**
     * The id of {@link #seedOneGroundingChunk}'s chunk — FIXED, because a scripted model response has
     * to name it.
     *
     * <p>Day 16 is why this constant exists. G4 checks every cited id against the chunks the request
     * actually supplied, so a test that scripts a grounded answer has to know, before the request is
     * made, which id that answer is allowed to cite. Everywhere else in the suite a chunk id is a
     * {@code UUID.randomUUID()} nobody looks at; here it is part of the contract between the fixture
     * and the canned response, so it is written down once and read from both ends.
     */
    public static final UUID GROUNDING_CHUNK_ID =
            UUID.fromString("00000000-0000-0000-0000-00000000ca11");

    /**
     * Replaces {@code kb_chunks} with ONE excerpt at distance 0 from
     * {@code PostgresBackedContext.queryVector()}, so any ticket retrieves it and any answer citing
     * {@link #GROUNDING_CHUNK_ID} passes the grounding gates.
     *
     * <p>Shared by the transport ITs, which are about the wire and not about retrieval, and which
     * before Day 16 could leave the corpus empty because nothing checked whether an answer had
     * anything to stand on. It is not free to leave empty any more: an empty corpus means no citable
     * id, which means every scripted "success" response would escalate at G4 and every transport
     * assertion would be reading the wrong outcome for the wrong reason.
     *
     * <p>Callers must clear the corpus in an {@code @AfterAll} — the Postgres container is shared, so
     * rows written here outlive the class that wrote them.
     */
    public static void seedOneGroundingChunk(ChunkRepository chunks, DocumentRepository documents) {
        chunks.deleteAllInBatch();
        float[] vector = new float[KbChunk.EMBEDDING_DIMENSION];
        vector[0] = 1.0f;
        chunks.save(new KbChunk(
                GROUNDING_CHUNK_ID,
                documentId(documents, "refund-policy.md"),
                "refund-policy.md", 0,
                "Refund Policy > Standard Refund Window",
                "Customers have 30 days from the delivery date to request a refund.",
                100, vector, "voyage-4-large"));
    }
}
