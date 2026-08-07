package org.aura.aura.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * The document ledger's read/write surface. Small on purpose: the pipeline needs to read every
 * document's fingerprint once per run, find one document by path, write one, and delete one.
 *
 * <p>The bulk read is plain {@link JpaRepository#findAll()} rather than a
 * {@code Map<String, String>}-shaped projection, and that is a size judgement rather than an
 * oversight. The corpus is a directory of policy documents — tens of rows, each carrying two short
 * strings and a hash — so loading whole entities costs nothing measurable and keeps one type flowing
 * through the pipeline instead of two. The moment this table holds thousands of rows, a projection
 * of {@code (path, fingerprint)} is the change to make, and it is a one-method change because
 * nothing outside {@code IngestionPipeline.storedFingerprints()} depends on the shape.
 */
public interface DocumentRepository extends JpaRepository<KbDocument, UUID> {

    /**
     * The document's identity to the pipeline is its PATH, not its uuid — the uuid is assigned at
     * first ingestion and is meaningless to a scan of the filesystem. {@code path} is UNIQUE in the
     * schema, which is what makes this an {@code Optional} rather than a list.
     */
    Optional<KbDocument> findByPath(String path);
}
