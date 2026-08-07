package org.aura.aura.ingest;

import lombok.extern.slf4j.Slf4j;
import org.aura.aura.canary.CanaryDocument;
import org.aura.aura.chunker.DocumentChunker;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.EmbeddingProperties;
import org.aura.aura.config.IngestionProperties;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.domain.Chunk;
import org.aura.aura.store.CanaryProbeRepository;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.DocumentRepository;
import org.aura.aura.store.KbChunk;
import org.aura.aura.store.KbDocument;
import org.aura.aura.util.VectorLiterals;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * INCREMENTAL ingestion: scan the corpus, work out what actually changed, and pay for exactly that.
 *
 * <h2>What it replaces</h2>
 * Day 13's loader had two behaviours — skip everything, or wipe the table and re-embed everything —
 * because the only question it could ask the database was "are there any rows?". Wipe-and-reload is
 * also an outage: between the DELETE and the last INSERT the corpus is incomplete and every query
 * runs against a partial knowledge base. Both problems have the same root, which is that the store
 * held no record of WHAT it held. {@code kb_documents} is that record, and everything below is
 * arithmetic on it.
 *
 * <h2>The pipeline</h2>
 * <pre>
 *   scan kb/ ──▶ fingerprint each ──▶ read the ledger ──▶ diff (IngestionPlan) ──▶ guard
 *                                                                                    │
 *          per NEW/CHANGED doc:  chunk ──▶ embedBatched ──▶ [ swap in one transaction ]
 *          per DELETED doc:                                 [ remove in one transaction ]
 * </pre>
 *
 * <h2>The canary rides the same rails, and lands somewhere else</h2>
 * {@link CanaryDocument} is a synthetic document with no file behind it, and it is deliberately NOT
 * special-cased anywhere above the write itself: it is fingerprinted by the same function, diffed by
 * the same plan, tracked by its own {@code kb_documents} row, and committed by the same per-document
 * transaction. That fate-sharing is what keeps the guard honest — a canary on a private code path is
 * a canary that can quietly stop being maintained by the machinery it exists to watch.
 *
 * <p>Exactly two things differ, and both are one line. It bypasses {@link DocumentChunker} (see
 * {@link #chunksOf}), because a chunker that derives breadcrumbs from headings would rewrite the
 * frozen text's embedding input. And its vector is upserted into {@code canary_probe} instead of
 * {@code kb_chunks} (see {@link #swap}), because {@code kb_chunks} is the retrieval corpus and a
 * measuring instrument does not belong in the population it measures.
 *
 * <h2>Programmatic transactions, and why {@code @Transactional} would not work here</h2>
 * The per-document swap has to be atomic: delete the old chunks, insert the new ones, advance the
 * fingerprint — all three or none, or the store ends up with a document whose fingerprint says
 * "current" and whose chunks are half-written.
 *
 * <p>The instinct is to annotate a {@code processDocument} method with {@code @Transactional} and
 * call it from the loop. It compiles, it reads correctly, and it does nothing at all. Spring
 * implements {@code @Transactional} with an AOP proxy that wraps this bean from the OUTSIDE, so it
 * can only intercept calls that cross the bean boundary. A loop inside this class calling
 * {@code this.processDocument(path)} never leaves the object, never touches the proxy, and the
 * annotation is inert — no error, no warning, no transaction, and partial writes on the first
 * failure. That is the self-invocation trap, and it is invisible precisely because the code says what
 * you meant.
 *
 * <p>The two real fixes are: move the per-document logic into a second bean, so the call crosses a
 * proxy boundary; or open the transaction programmatically. The second bean works and is a common
 * answer, but it splits one cohesive flow across two classes for a framework reason, and the reader
 * of the second class has no way to tell it exists because of AOP mechanics. So this uses
 * {@link TransactionTemplate}. The cost is explicit transaction code instead of a declarative
 * annotation; for a loop of INDEPENDENT transactions — where the whole point is that document B's
 * failure does not roll back document A — the explicit boundary is arguably the clearer form anyway.
 *
 * <h2>The embedding call is deliberately OUTSIDE the transaction</h2>
 * {@code chunk → embedBatched → swap}, in that order, and the network call finishes before the
 * transaction opens. Holding a database transaction across an HTTP request would pin one of Hikari's
 * ten connections for the provider's entire latency — retries included — which is the Day 14 review's
 * FINDING 3 arriving for real. It also gives failure isolation for free: a document whose embedding
 * throws never opens a transaction, so there is nothing to roll back and nothing partially written.
 */
@Slf4j
@Component
// Absent means off. Beyond keeping the runner quiet, this is what stops the bean — and therefore its
// ChunkRepository and DocumentRepository dependencies — from being constructed in the many contexts
// that have no database, which is why the condition is on the class rather than an `if` inside run().
@ConditionalOnProperty(name = "aura.ingest.enabled", havingValue = "true")
public class IngestionPipeline implements ApplicationRunner {

    /**
     * The destructive-plan threshold: refuse when a run would delete MORE THAN half the documents the
     * ledger holds.
     *
     * <p>Half is a judgement, not a measurement, and it is worth being honest about that. What is not
     * a judgement is the shape of the mistake it catches: the realistic way a corpus gets destroyed is
     * not someone deleting files one by one, it is the scan pointing somewhere unexpected — a wrong
     * working directory, a container without the volume mounted, a renamed folder — and returning a
     * fraction of what should be there. That mistake is always large. Legitimate curation is always
     * small. A threshold anywhere in the middle separates them, so the exact value matters much less
     * than having one.
     */
    private static final double MAX_DELETE_RATIO = 0.5;

    private static final String MARKDOWN_SUFFIX = ".md";

    /**
     * The same ~4-characters-per-token English proxy the chunker uses for its size cap, kept for
     * {@code kb_chunks.token_count}. Approximate, and stated as such wherever it appears: there is no
     * clean JVM build of Voyage's tokenizer, and the real per-request token count the API returns is
     * for the batch as a whole, so attributing it back to individual chunks would invent a per-chunk
     * number that looks measured and is not.
     */
    private static final int CHARS_PER_TOKEN = 4;

    private final DocumentChunker chunker;
    private final VoyageEmbeddingClient voyage;
    private final ChunkRepository chunks;
    private final DocumentRepository documents;
    private final CanaryProbeRepository probe;
    private final VoyageProperties voyageProps;
    private final EmbeddingProperties embeddingProps;
    private final IngestionProperties ingestProps;
    private final TransactionTemplate tx;

    public IngestionPipeline(DocumentChunker chunker,
                             VoyageEmbeddingClient voyage,
                             ChunkRepository chunks,
                             DocumentRepository documents,
                             CanaryProbeRepository probe,
                             VoyageProperties voyageProps,
                             EmbeddingProperties embeddingProps,
                             IngestionProperties ingestProps,
                             TransactionTemplate tx) {
        this.chunker = chunker;
        this.voyage = voyage;
        this.chunks = chunks;
        this.documents = documents;
        this.probe = probe;
        this.voyageProps = voyageProps;
        this.embeddingProps = embeddingProps;
        this.ingestProps = ingestProps;
        this.tx = tx;
    }

    /**
     * The trigger: an {@code ApplicationRunner}, not an admin REST endpoint.
     *
     * <p>An endpoint would be more convenient and is the obvious next step — but an UNAUTHENTICATED
     * endpoint that can rewrite the knowledge base is a guardrails problem, not a feature, and this
     * application has no authentication until Day 18. Shipping the endpoint first and securing it
     * later is the order that produces the incident. Parked until there is something to put in front
     * of it.
     */
    @Override
    public void run(ApplicationArguments args) {
        ingest();
    }

    /**
     * Runs one ingestion pass.
     *
     * <p>Public and separate from {@link #run} so a test — or a demo that needs a populated corpus —
     * can drive it directly instead of faking an application startup to get one.
     *
     * @throws IngestionRefusedException when the plan trips a guard and {@code aura.ingest.force} is
     *         not set. Nothing has been written when this is thrown; the guards run before the first
     *         embedding call
     */
    public IngestReport ingest() {
        long startedAt = System.nanoTime();
        Path corpusDir = Path.of(ingestProps.dir());

        Map<String, String> content = scan(corpusDir);
        // COUNTED BEFORE the canary joins the map, and this line is the whole reason the count is a
        // variable rather than content.size() read later. The canary is synthesised, not scanned, so
        // it is always present — and a zero-docs-found guard that included it would never fire, which
        // is to say the guard would exist and never work.
        int filesFound = content.size();
        content.put(CanaryDocument.PATH, CanaryDocument.fingerprintContent());

        String modelId = voyageProps.documentModel();
        int dimension = embeddingProps.dimension();

        Map<String, String> onDisk = new TreeMap<>();
        content.forEach((path, text) ->
                onDisk.put(path, DocumentFingerprinter.fingerprint(text, modelId, dimension)));
        Map<String, String> stored = storedFingerprints();

        IngestionPlan plan = IngestionPlan.of(onDisk, stored);
        log.info("ingestion plan — {} new, {} changed, {} unchanged, {} deleted "
                        + "({} markdown files under {}, {} documents in the ledger)",
                plan.added().size(), plan.changed().size(), plan.unchanged().size(),
                plan.deleted().size(), filesFound, corpusDir.toAbsolutePath(), stored.size());

        guard(plan, filesFound, stored.size());

        List<IngestReport.Failure> failed = new ArrayList<>();
        int embeddingCalls = 0;

        for (String path : plan.toEmbed()) {
            try {
                embeddingCalls += rebuild(path, content.get(path), onDisk.get(path), modelId, dimension);
            } catch (RuntimeException e) {
                // ONE DOCUMENT'S FAILURE IS ONE DOCUMENT'S FAILURE. Catching here rather than letting
                // it propagate is what makes a Voyage hiccup on the third of five documents cost the
                // third document instead of the run — and because the fingerprint is advanced inside
                // the same transaction as the chunks, this document is simply still "changed" on the
                // next run and gets retried then. Doing nothing is a valid recovery.
                failed.add(new IngestReport.Failure(path, describe(e)));
                log.error("ingestion FAILED for {} — the other documents in this run are unaffected, "
                        + "and this one stays pending (its fingerprint was not advanced)", path, e);
            }
        }

        for (String path : plan.deleted()) {
            try {
                remove(path);
            } catch (RuntimeException e) {
                failed.add(new IngestReport.Failure(path, describe(e)));
                log.error("ingestion FAILED to remove {}", path, e);
            }
        }

        IngestReport report = new IngestReport(
                plan.added().size(), plan.changed().size(), plan.unchanged().size(),
                plan.deleted().size(), failed,
                Duration.ofNanos(System.nanoTime() - startedAt), embeddingCalls);

        // embeddingCalls is logged even on a no-op run, at INFO, because zero is the interesting
        // value: it is the proof that a second run over an unchanged corpus cost nothing, and a
        // number that only appears when work happened cannot demonstrate the absence of work.
        log.info("ingestion COMPLETE — {} new, {} changed, {} unchanged, {} deleted, {} failed; "
                        + "{} embedding call(s) in {}ms (model={}, chunker={})",
                report.added(), report.changed(), report.unchanged(), report.deleted(),
                report.failed().size(), report.embeddingCalls(), report.duration().toMillis(),
                modelId, DocumentChunker.CHUNKER_VERSION);

        return report;
    }

    // ---------------------------------------------------------------- scan + fingerprint

    /**
     * Reads every {@code *.md} in the corpus directory, keyed by file name.
     *
     * <p>File NAME, not full path: it is what {@code kb_chunks.source_doc} already holds and what a
     * citation shows a human, and keying the ledger on an absolute path would make the fingerprint
     * map differ between a developer's machine and a container for no reason a reader could see.
     *
     * <p>A missing directory returns EMPTY rather than throwing, which looks lenient and is not. It
     * is the same event as an empty directory — the scan found nothing — and the decision about what
     * "found nothing" means belongs to {@link #guard}, which knows whether the store is populated.
     * Throwing here would answer that question in the wrong place and with less information.
     */
    private Map<String, String> scan(Path corpusDir) {
        Map<String, String> byName = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(corpusDir)) {
            // Sorted, so two runs on two machines process documents in the same order. Nothing
            // downstream depends on it; it exists so that two runs can be diffed.
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(MARKDOWN_SUFFIX))
                    .sorted()
                    .forEach(path -> byName.put(path.getFileName().toString(), read(path)));
        } catch (NoSuchFileException e) {
            log.warn("ingestion scan found NO DIRECTORY at {} — aura.ingest.dir is resolved against "
                            + "the working directory. Treating this as zero documents found; the guard "
                            + "below decides whether that is allowed.",
                    corpusDir.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "cannot read the knowledge-base corpus at " + corpusDir.toAbsolutePath(), e);
        }
        return byName;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read corpus file " + path, e);
        }
    }

    private Map<String, String> storedFingerprints() {
        Map<String, String> stored = new TreeMap<>();
        documents.findAll().forEach(doc -> stored.put(doc.getPath(), doc.getFingerprint()));
        return stored;
    }

    // ---------------------------------------------------------------- the guard

    /**
     * Refuses plans that look like accidents.
     *
     * <p>Both rules protect the same asset — a populated store — against the same class of mistake,
     * which is a scan that silently saw less than it should have. Neither can fire when the ledger is
     * empty, because there is then nothing to lose and a first run legitimately deletes nothing.
     *
     * <p>{@code aura.ingest.force=true} overrides both, loudly. The flag exists because "delete most
     * of the corpus" is a real operation — retiring a product line takes half the policy documents
     * with it — and a guard with no override is a guard that gets deleted the first time someone
     * needs to do the legitimate thing.
     */
    private void guard(IngestionPlan plan, int filesFound, int storedDocuments) {
        if (storedDocuments == 0) return;

        if (filesFound == 0) {
            // Checked SEPARATELY from the ratio below even though a zero scan usually trips that too.
            // "Usually" is the problem: with one document in the ledger, deleting it is 1 of 1, and
            // `1 > 0.5` holds — but the message would then blame a ratio when the actual finding is
            // that the corpus directory is empty or wrong. Two rules, two diagnoses.
            refuseUnlessForced("found NO markdown files, but the ledger holds " + storedDocuments
                    + " document(s). This is what a wrong working directory, an unmounted volume or a "
                    + "renamed corpus folder looks like, and carrying on would delete the entire "
                    + "knowledge base");
            return;
        }

        int deleting = plan.deleted().size();
        if (deleting > storedDocuments * MAX_DELETE_RATIO) {
            refuseUnlessForced("would delete " + deleting + " of " + storedDocuments
                    + " document(s) — more than the " + (int) (MAX_DELETE_RATIO * 100)
                    + "% this pipeline will remove without being told to. Deleted: " + plan.deleted());
        }
    }

    private void refuseUnlessForced(String finding) {
        if (ingestProps.force()) {
            log.warn("ingestion guard OVERRIDDEN by aura.ingest.force=true — the pipeline {}. "
                    + "Proceeding because it was explicitly told to.", finding);
            return;
        }
        throw new IngestionRefusedException("ingestion REFUSED — the pipeline " + finding
                + ". Nothing has been written. If this is intentional, re-run with "
                + "-Daura.ingest.force=true; if it is not, fix aura.ingest.dir first.");
    }

    // ---------------------------------------------------------------- per-document work

    /**
     * Rebuilds one document: chunk it, embed it, then swap its chunks and advance its fingerprint
     * inside a single transaction.
     *
     * @return the number of HTTP calls the embedding cost, for the report
     */
    private int rebuild(String path, String text, String fingerprint, String modelId, int dimension) {
        List<Chunk> docChunks = chunksOf(path, text);

        if (docChunks.isEmpty()) {
            // A markdown file that chunks to nothing — empty, or nothing but an HTML comment. Its
            // fingerprint is still advanced, with zero chunks written, and that is the point: leaving
            // it un-advanced would make the pipeline re-decide the same nothing on every future run
            // and report a permanently pending document.
            log.warn("ingestion — {} produced no chunks (empty or comment-only); recording it with "
                    + "zero chunks so it is not re-attempted every run", path);
            tx.executeWithoutResult(status -> swap(path, fingerprint, modelId, List.of(), List.of()));
            return 0;
        }

        // OUTSIDE the transaction, on purpose — see the class javadoc. If this throws, the caller
        // records the failure and no transaction was ever opened, so there is nothing half-written
        // and the fingerprint still says "pending".
        VoyageEmbeddingClient.BatchedEmbeddings embedded = voyage.embedBatched(
                docChunks.stream().map(Chunk::embeddingInput).toList());

        if (embedded.vectors().size() != docChunks.size()) {
            throw new IllegalStateException("embedding returned " + embedded.vectors().size()
                    + " vectors for " + docChunks.size() + " chunks of " + path);
        }
        if (embedded.vectors().stream().anyMatch(vector -> vector.length != dimension)) {
            // Caught here rather than by Postgres, purely for the diagnosis. The vector(1024) column
            // WOULD reject it — that is the schema's best property — but it would do so during a
            // flush, naming a column and a row rather than a document and a model.
            throw new IllegalStateException("embedding for " + path + " is not "
                    + dimension + "-dimensional — check voyage.document-model against "
                    + "aura.embedding.dimension");
        }

        // THE PROGRAMMATIC TRANSACTION. One call to TransactionTemplate per document, so each
        // document's swap commits or rolls back on its own — which is the property the failure-
        // isolation requirement is actually asking for, and the one a single @Transactional around
        // the whole loop would destroy even if the proxy did intercept it.
        tx.executeWithoutResult(status ->
                swap(path, fingerprint, modelId, docChunks, embedded.vectors()));
        return embedded.calls();
    }

    /**
     * The transactional body: three writes that must land together.
     *
     * <p>Ordering is EXPLICIT rather than left to Hibernate's flush ordering, and that is deliberate.
     * {@link KbChunk} holds a raw {@code document_id} instead of a {@code @ManyToOne}, so Hibernate
     * does not know the two entities are related and cannot order the inserts for us. Worse, its
     * default flush order puts every insert before every delete — which on a CHANGED document means
     * the new chunks are inserted while the old ones are still there, and they collide on
     * {@code UNIQUE (source_doc, chunk_index)}. {@code saveAndFlush} and the {@code @Modifying} bulk
     * delete both hit the database when they are called, so the sequence on the wire is the sequence
     * written here.
     */
    private void swap(String path, String fingerprint, String modelId,
                      List<Chunk> docChunks, List<float[]> vectors) {
        KbDocument document = documents.findByPath(path).orElse(null);

        if (document == null) {
            document = new KbDocument(UUID.randomUUID(), path, fingerprint, modelId,
                    DocumentChunker.CHUNKER_VERSION);
            // Flushed before the chunks are built so the parent row exists when the FK is checked.
            documents.saveAndFlush(document);
        } else {
            int removed = chunks.deleteByDocumentId(document.getId());
            log.debug("ingestion swap — removed {} existing chunk(s) for {}", removed, path);
        }

        // THE ONE BRANCH. Everything around it is identical for the canary and for a policy
        // document — the same scan produced them, the same fingerprint function judged them, the same
        // plan scheduled them, the same ledger row tracks them, and this same transaction commits
        // them. Only the DESTINATION of the vectors differs, because only the destination should:
        // kb_chunks is the retrieval corpus and the canary is not a candidate answer.
        //
        // Keeping the branch this narrow is deliberate. A canary that took a separate code path would
        // be a canary that stops being exercised by the machinery it is supposed to be watching — it
        // could silently stop being planned, stop being fingerprinted, or stop sharing a transaction,
        // and nothing would notice until the guard was needed.
        if (CanaryDocument.PATH.equals(path)) {
            if (docChunks.size() != 1) {
                // Structurally impossible today — chunksOf returns List.of(CanaryDocument.chunk()) —
                // and asserted anyway because the failure is silent: getFirst() would pick one vector
                // out of several and the probe would measure against an arbitrary fragment.
                throw new IllegalStateException("the canary must produce exactly one chunk, got "
                        + docChunks.size());
            }
            probe.upsert(VectorLiterals.toLiteral(vectors.getFirst()), modelId);
        } else {
            UUID documentId = document.getId();
            List<KbChunk> rows = new ArrayList<>(docChunks.size());
            for (int i = 0; i < docChunks.size(); i++) {
                Chunk chunk = docChunks.get(i);
                rows.add(new KbChunk(
                        UUID.randomUUID(),
                        documentId,
                        chunk.sourceDoc(),
                        chunk.position(),
                        chunk.breadcrumb(),
                        chunk.text(),
                        estimateTokens(chunk.embeddingInput()),
                        vectors.get(i),
                        // Stamped from the SAME config that routed the embedding call, in the same run
                        // — not from a constant, and not re-read later. That is what makes the stored
                        // model name a fact about this row rather than a guess about the deployment.
                        modelId));
            }
            chunks.saveAll(rows);
        }

        // LAST. The fingerprint is the pipeline's only record that this document's work is done, so
        // it must be the last thing to become true. Advancing it earlier and failing afterwards would
        // mark a half-written document as current, and every later run would agree and skip it.
        document.advanceTo(fingerprint, modelId, DocumentChunker.CHUNKER_VERSION);
        documents.save(document);
    }

    /**
     * Removes a document that is no longer on disk, chunks first.
     *
     * <p>The explicit chunk delete is redundant with {@code ON DELETE CASCADE} and stays anyway. The
     * two live at different layers and fail independently: the cascade cannot be forgotten by a
     * refactor of this method, and this method cannot be disabled by someone rebuilding the schema
     * without the constraint. Belt and braces is only worth the name when the two are not the same
     * thing twice.
     */
    private void remove(String path) {
        tx.executeWithoutResult(status -> documents.findByPath(path).ifPresent(document -> {
            int removed = chunks.deleteByDocumentId(document.getId());
            documents.delete(document);
            log.info("ingestion — removed {} and its {} chunk(s); the file is no longer in the corpus",
                    path, removed);
        }));
    }

    /**
     * The chunks for one document — the chunker's output, except for the canary.
     *
     * <p>{@link CanaryDocument} is a SYNTHETIC document whose whole purpose is to hold text that
     * nothing can move, so running it through the chunker would defeat it twice over: the chunker
     * derives a breadcrumb from headings and falls back to the file name, so the canary's embedding
     * input would silently become {@code "__canary__\n…"}, and it would then change again the day the
     * chunker's breadcrumb rules do. Its bytes are frozen in code and reach the embedder untouched.
     */
    private List<Chunk> chunksOf(String path, String text) {
        if (CanaryDocument.PATH.equals(path)) return List.of(CanaryDocument.chunk());
        return chunker.chunk(text, path);
    }

    // Ceiling division: a 1-character chunk is one token, not zero. Rounding down is the more natural
    // integer division and would report a free chunk.
    private static int estimateTokens(String text) {
        return Math.ceilDiv(text.length(), CHARS_PER_TOKEN);
    }

    // Type AND message. The type alone ("VoyageTransientException") does not say which model or
    // status; the message alone does not say whether it was transient, which is the first thing an
    // operator reading a failure list wants to know.
    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
