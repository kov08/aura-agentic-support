-- The canary's vector, moved OUT of the retrieval corpus and into a table of its own.
--
-- V3 shipped the canary as a synthetic one-chunk document in kb_chunks, and named the cost out loud
-- on CanaryDocument: that row duplicates refund-policy.md chunk 0, so a refund query matches both and
-- spends part of a 700-token context budget twice on one passage. It was accepted as small and
-- fixable. This is the fix.
--
-- The deeper reason it was always wrong: kb_chunks is the RETRIEVAL CORPUS — every row in it is a
-- candidate answer to a customer question. The canary is not a candidate answer. It is a measuring
-- instrument that happens to be shaped like one, and storing an instrument in the population it
-- measures is how the instrument ends up in the results. Separating them by TABLE rather than by a
-- WHERE clause on the hot-path query means retrieval cannot see it even by accident, and no future
-- query has to remember to exclude it.
CREATE TABLE canary_probe
(
    -- ONE ROW, ENFORCED BY THE DATABASE. The CHECK is what makes "there is exactly one probe" a
    -- schema fact instead of a convention every writer has to honour: no second row can be inserted,
    -- whatever the application believes. Same family of guarantee as vector(1024) rejecting a
    -- mis-sized embedding and as kb_chunks' ON DELETE CASCADE forbidding an orphan — the pattern
    -- throughout this schema is that the invariant lives where it cannot be bypassed.
    --
    -- smallint rather than uuid or bigserial because the value carries no information: it is not an
    -- identity to look anything up by, it is the constant the CHECK pins. Making it wide or generated
    -- would imply a variability the constraint forbids.
    id                 smallint     PRIMARY KEY,

    -- The stored side of the boot comparison: a voyage-4-large/document vector of the frozen text in
    -- CanaryDocument. Same width and same type as kb_chunks.embedding on purpose — the measurement
    -- only means something if it uses the same arithmetic the ranked search uses, and `<=>` between
    -- two vector(1024) values is that arithmetic.
    embedding          vector(1024) NOT NULL,

    -- Provenance, per row, for the same reason kb_chunks.embedding_model exists: the diagnostic that
    -- a canary trip prints has to report the model that ACTUALLY produced the stored vector, read off
    -- the row, rather than the model configuration currently claims. Day 14's lane-flip drill is the
    -- whole argument — a guard whose diagnosis states an intention is worse than no diagnosis.
    embedding_model_id text         NOT NULL,

    -- Written by Postgres in both paths: DEFAULT now() on insert, and an explicit now() in the
    -- upsert's DO UPDATE branch. No trigger is needed here (unlike kb_documents) because there is
    -- exactly one writer statement and it can set the column itself — and now() is evaluated
    -- server-side, so it is still the database's clock and not a JVM's.
    updated_at         timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT canary_probe_is_one_row CHECK (id = 1)
);

-- CLEANUP, part 1: evict the canary from the retrieval corpus.
--
-- The literal '__canary__' duplicates CanaryDocument.PATH, and the duplication is irreducible for the
-- same reason `vector(1024)` is duplicated across three files: a migration cannot read a Java
-- constant. It is safer here than there, though, because this statement is a ONE-SHOT against
-- historical data — it runs once, against rows written by a version of the code where that constant
-- had this value, and it can never drift with future code the way a live query would.
DELETE FROM kb_chunks WHERE source_doc = '__canary__';

-- CLEANUP, part 2: make the ledger tell the truth again.
--
-- This is the statement the migration does not work without, and the reason is a loop that closes
-- through the database. The pipeline decides what to do by comparing a computed fingerprint against
-- kb_documents.fingerprint. The DELETE above just destroyed the canary's stored vector — but the
-- canary's content, chunker version, embedding model and dimension are all unchanged, so its computed
-- fingerprint is unchanged too. The next run would compare them, find them equal, report the canary
-- 'unchanged', write nothing, and leave canary_probe empty forever. The boot check would then skip on
-- an absent probe on every boot, and the guard would be silently switched off by a migration.
--
-- So the fingerprint is invalidated. The kb_documents row itself STAYS — the canary keeps flowing
-- through the same scan, the same plan and the same per-document transaction as a real document, and
-- that fate-sharing is the point of the design. Only its claim to be current is withdrawn, which is
-- accurate: after the DELETE above, it is not current.
--
-- The sentinel is deliberately not a hash. Fingerprints are 64 hex characters, so this value cannot
-- collide with a real one, and an operator reading the table sees why the row is stale instead of
-- seeing a hash that happens not to match.
UPDATE kb_documents
SET fingerprint = 'stale:V4-canary-probe-extraction'
WHERE path = '__canary__';
