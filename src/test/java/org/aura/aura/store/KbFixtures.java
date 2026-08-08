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
}
