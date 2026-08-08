-- The DOCUMENT ledger: one row per source file the pipeline has ingested, and the fingerprint that
-- says which version of it the store currently holds.
--
-- V2 deliberately did not have this. Its comment on `source_doc` said so out loud: "there is no
-- document entity yet, and inventing one now would be a schema built for an imagined Day 15 rather
-- than the one that ships." This is that Day 15, and the entity is no longer imagined — the
-- incremental pipeline cannot exist without somewhere to record "what did we ingest, and from what".
--
-- The whole idempotency story lives in one column. `fingerprint` is a hash over the document's
-- normalised content PLUS the configuration that turned it into vectors, so "has this document
-- already been ingested under the current regime?" is a string comparison rather than a judgement.
-- Everything else in the pipeline is arithmetic on that answer.
CREATE TABLE kb_documents
(
    -- APP-ASSIGNED, same reasoning as kb_chunks.id: the pipeline needs a document's identity before
    -- it inserts the chunks that point at it, and a bigserial would force a round-trip in the middle
    -- of a transaction to learn a number it could have picked itself.
    id                 uuid        PRIMARY KEY,

    -- The file name as scanned (e.g. 'refund-policy.md'), which is also kb_chunks.source_doc. UNIQUE
    -- because the path IS the document's identity to the pipeline: the plan is computed by diffing a
    -- map keyed on this value against a map keyed on this column, and a duplicate would make that
    -- diff ambiguous in a way no code path is written to handle.
    path               text        NOT NULL UNIQUE,

    -- SHA-256 hex over normalised content + chunker version + embedding model id + dimension. Note
    -- what is deliberately NOT in it: the file's mtime, its size, its inode. Those are properties of
    -- the filesystem, not of the document, and they change on a `git checkout` that restores
    -- byte-identical content — which would make every clone re-embed the entire corpus for money.
    fingerprint        text        NOT NULL,

    -- The next two columns are ALREADY INSIDE the fingerprint, and storing them separately is not
    -- redundancy by accident. A hash answers "is this the same?" and refuses to answer "same as
    -- WHAT?". When a corpus looks wrong at 2am, the question is which era each document came from,
    -- and these two columns answer it by being read — the alternative is reverse-engineering a hash,
    -- which is not an operation.
    --
    -- They are diagnostics, and nothing branches on them. If something ever does, this comment is the
    -- warning that the fingerprint is the authority and these are its shadow.
    embedding_model_id text        NOT NULL,
    chunker_version    text        NOT NULL,

    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

-- ONE WRITER PER FIELD, and here that writer is Postgres for both timestamps.
--
-- The obvious alternative is to set updated_at from the application on each write. It is also the
-- version that produces a row whose created_at came from the database's clock and whose updated_at
-- came from a JVM's — two clocks, one row, disagreeing by whatever the host drift happens to be. That
-- is a debugging problem nobody should have to have, and a DEFAULT alone cannot fix it because a
-- column default fires on INSERT and never on UPDATE. A BEFORE UPDATE trigger is what closes that
-- gap without handing the second clock a vote.
CREATE OR REPLACE FUNCTION kb_documents_touch_updated_at() RETURNS trigger AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER kb_documents_touch_updated_at
    BEFORE UPDATE
    ON kb_documents
    FOR EACH ROW
EXECUTE FUNCTION kb_documents_touch_updated_at();

-- DELETING DATA IN A MIGRATION, on purpose, and the distinction that makes it legitimate rather than
-- a firing offence is which side of the derived/source line these rows sit on.
--
-- kb_chunks is a DERIVED artefact. The source of truth is kb/, a directory of markdown files under
-- version control; every chunk in this table was computed from those files and can be recomputed from
-- them. The Day 13 rows have no parent document — they were written before kb_documents existed —
-- so there is no fingerprint to give them and no honest value for the NOT NULL column added below.
-- Backfilling a synthetic parent would be worse than deleting: it would assert a provenance that was
-- never measured, and the first pipeline run would then believe a corpus ingested under Day 13's
-- rules was current under Day 15's.
--
-- So the rows go, and the first `aura.ingest.enabled=true` run rebuilds the store. Deleting SOURCE
-- data in a migration would be unforgivable; deleting a cache you can regenerate is housekeeping.
--
-- Operational consequence, stated rather than discovered: between this migration and that first run,
-- retrieval returns nothing and the boot canary skips on an empty corpus. Ingest before serving.
DELETE FROM kb_chunks;

-- The join back to the ledger. NOT NULL is the point: after this migration it is not possible to
-- have a chunk that no document accounts for, which is exactly the state the Day 13 rows above were
-- in.
--
-- ON DELETE CASCADE is a GUARDRAIL, not the mechanism. IngestionPipeline deletes a document's chunks
-- explicitly before it deletes the document — belt — and this clause makes an orphan unrepresentable
-- even if that code is wrong, refactored, or bypassed by a hand-run DELETE at 2am — braces. Two
-- layers, and they fail independently: a bug in the pipeline cannot disable the constraint, and a
-- constraint cannot be forgotten the way an explicit delete can.
ALTER TABLE kb_chunks
    ADD COLUMN document_id uuid NOT NULL
        REFERENCES kb_documents (id) ON DELETE CASCADE;

-- Postgres does NOT index a foreign key automatically, and the two operations this pipeline performs
-- most are both keyed on it: delete every chunk of one document, then insert its replacements. Without
-- this index each of those is a sequential scan, and — less obviously — so is the cascade check
-- Postgres runs when a parent row is deleted.
CREATE INDEX kb_chunks_document_id_idx ON kb_chunks (document_id);
