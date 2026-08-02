-- The retrieval corpus: one row per embeddable chunk of the knowledge base.
--
-- This table is the persistent form of the Day 12 `Chunk` record plus its vector. The in-memory
-- brute-force scan in the Day 12 demo held exactly this data in an ArrayList; everything below is
-- that list, given durability, a uniqueness rule, and an ordering operator.
CREATE TABLE kb_chunks
(
    -- APP-ASSIGNED uuid, not a database sequence. Two reasons, in order of weight: the application
    -- knows a chunk's identity before it touches the database, so a batch insert needs no round-trip
    -- per row to learn its own keys; and a test can construct a KbChunk with a fixed id and assert on
    -- it without a database at all. A bigserial would force both of those through Postgres.
    id              uuid        PRIMARY KEY,

    -- The citation handle. Kept as the file name (e.g. 'refund-policy.md') rather than a foreign key
    -- to a documents table: there is no document entity yet, and inventing one now would be a schema
    -- built for an imagined Day 15 rather than the one that ships.
    source_doc      text        NOT NULL,

    -- The chunk's 0-based ordinal within its source document, in reading order (Chunk.position).
    chunk_index     int         NOT NULL,

    -- The heading path, e.g. 'Refund Policy > International Orders'. Stored SEPARATELY from content
    -- even though Chunk.embeddingInput() concatenates the two before embedding, because the two have
    -- different jobs downstream: the breadcrumb is what a citation shows a human, the content is what
    -- an answer is grounded in. Storing only the concatenation would make the citation a substring
    -- operation on prose, which is exactly the kind of parsing that goes wrong silently.
    breadcrumb      text        NOT NULL,

    content         text        NOT NULL,

    -- An APPROXIMATION, and stated as one — the same ~4-chars-per-token English heuristic the chunker
    -- uses for its size cap, because there is no clean JVM build of Voyage's tokenizer. It is stored
    -- for cost accounting and chunk-size diagnostics, and nothing reads it to make a decision. If
    -- something ever does, this comment is the warning that it is not a measured value.
    token_count     int         NOT NULL,

    -- The dimension is part of the COLUMN TYPE, not a check constraint, because pgvector makes it
    -- part of the type. That is the single most valuable thing this schema does: a 512-dimension
    -- vector inserted here does not get stored-and-mis-ranked, it is rejected by the database. The
    -- literal 1024 is duplicated in aura.embedding.dimension, and that duplication is checked at
    -- every boot (see EmbeddingDimensionCheck) rather than trusted.
    embedding       vector(1024) NOT NULL,

    -- WHICH MODEL PRODUCED THE VECTOR. Not bookkeeping — the Day 12 lab (lab/CrossModelDemoIT)
    -- measured what happens without it: re-point the query lane at a different model era without
    -- re-embedding the corpus, and every similarity score collapses toward zero while nothing throws,
    -- nothing warns, and the build stays green. The configuration is internally valid at every
    -- instant; only the DATA knows it is stale. This column is what lets that be detected at all.
    embedding_model text        NOT NULL,

    created_at      timestamptz NOT NULL DEFAULT now(),

    -- Identity of a chunk = (document, position within it). This is the constraint that makes a
    -- re-ingestion a conflict instead of a silent duplicate — without it, running the loader twice
    -- doubles the corpus and every query returns the same passage twice at the top. Day 15 turns this
    -- into an ON CONFLICT upsert target; today it is the tripwire that makes the missing upsert loud.
    CONSTRAINT kb_chunks_doc_position_unique UNIQUE (source_doc, chunk_index)
);

-- NO VECTOR INDEX, on purpose.
--
-- An HNSW or IVFFlat index is an APPROXIMATE nearest-neighbour structure: it buys speed by agreeing
-- to sometimes not return the true top-k. At the current corpus size (three policy documents, tens of
-- chunks) a sequential scan is both exact and instant, so an index here would trade away recall for a
-- latency win that does not exist — and it would do it invisibly, because a wrong top-k looks exactly
-- like a right one.
--
-- Adding one is therefore a decision with a real trade-off, not a performance tweak, and it gets an
-- ADR when a measurement — not a hunch — says the scan has become the latency budget. The trigger to
-- write that ADR: sequential-scan latency at p95 becoming a visible share of the per-ticket budget,
-- which at these vector sizes means the corpus reaching the low tens of thousands of chunks. Until
-- then the honest index is no index.
--
-- One consequence worth stating now so it is not a surprise later: an HNSW index is built FOR a
-- specific distance operator, so choosing `<=>` (cosine) here also fixes which index can serve it.
-- The operator in ChunkRepository is written out explicitly for that reason.
