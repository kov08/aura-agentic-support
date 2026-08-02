package org.aura.aura.ingest;

import lombok.extern.slf4j.Slf4j;
import org.aura.aura.chunker.DocumentChunker;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.domain.Chunk;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.KbChunk;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The one-shot ingestion pass: {@code kb/} → {@link DocumentChunker} → Voyage (document lane) →
 * {@code kb_chunks}. It is the thing the Day 12 demo did in memory on every run, done once and
 * persisted.
 *
 * <h2>Why it is behind a flag</h2>
 * This is an expensive, billable, minutes-long batch job, and it has no business running because
 * someone restarted the application. {@code aura.kb.load=true} makes ingestion a deliberate act:
 *
 * <pre>{@code ./mvnw spring-boot:run -Daura.kb.load=true}</pre>
 *
 * <p>The {@code @ConditionalOnProperty} does double duty. It keeps the runner from firing, and it
 * keeps this bean — and therefore its {@link ChunkRepository} dependency — from being created at all
 * in the many contexts that have no database. That is why the flag is on the class rather than an
 * {@code if} inside {@link #run}.
 *
 * <h2>Re-running it</h2>
 * A populated table means the work is already done, so the default is to log and skip. That default
 * is the conservative one on both axes that matter: re-embedding costs real money, and blindly
 * re-inserting would collide with {@code UNIQUE (source_doc, chunk_index)} partway through and leave
 * the corpus half-written. {@code aura.kb.force-reload=true} wipes and re-ingests.
 *
 * <p>Wipe-and-reload is NOT an upsert, and the difference is a real outage window: between the delete
 * and the last insert, the corpus is incomplete and every query runs against a partial knowledge base.
 * That is acceptable for a one-shot developer operation on a corpus of tens of chunks and would not be
 * acceptable in production. Day 15 replaces this with a proper idempotent upsert (an
 * {@code ON CONFLICT (source_doc, chunk_index) DO UPDATE} against the constraint V2 already declares),
 * which is why the constraint exists now and the upsert does not.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.kb.load", havingValue = "true")
public class KbCorpusLoader implements ApplicationRunner {

    /**
     * How many chunk texts go into one Voyage call. A batch is one HTTP round-trip and one retry unit,
     * so bigger is cheaper right up until it isn't: the provider caps both the number of inputs and
     * the total tokens per request, and a single oversized batch fails whole rather than degrading.
     * 64 sits comfortably under those caps for ~500-token chunks and keeps a transient failure's blast
     * radius to one batch instead of the entire corpus.
     */
    private static final int BATCH_SIZE = 64;

    /**
     * The SAME ~4-characters-per-token English proxy the chunker uses for its size cap, and it is an
     * approximation for the same reason: there is no clean JVM build of Voyage's tokenizer. Voyage
     * does report a real token count per request, but only for the batch as a whole — attributing it
     * back to individual chunks would mean dividing by input count and inventing a per-chunk number
     * that looks measured and is not. An honest approximation beats a laundered one.
     */
    private static final int CHARS_PER_TOKEN = 4;

    private final DocumentChunker chunker;
    private final VoyageEmbeddingClient voyage;
    private final ChunkRepository repository;
    private final VoyageProperties voyageProps;
    private final Path corpusDir;
    private final boolean forceReload;

    public KbCorpusLoader(DocumentChunker chunker,
                          VoyageEmbeddingClient voyage,
                          ChunkRepository repository,
                          VoyageProperties voyageProps,
                          // Injected as a String and converted here rather than bound straight to a
                          // Path. Spring CAN produce a Path from a string, but it does it through
                          // PathEditor, which first tries to resolve the value as a Resource — so a
                          // value containing a colon is read as a URL scheme and a plain name may come
                          // back absolutised. None of that is wrong, exactly; it is just more
                          // behaviour than "the directory the corpus is in" needs, and the extra
                          // behaviour is the part that surprises someone at 2am.
                          @Value("${aura.kb.dir:kb}") String corpusDir,
                          @Value("${aura.kb.force-reload:false}") boolean forceReload) {
        this.chunker = chunker;
        this.voyage = voyage;
        this.repository = repository;
        this.voyageProps = voyageProps;
        this.corpusDir = Path.of(corpusDir);
        this.forceReload = forceReload;
    }

    /**
     * What one call to {@link #load()} did. Returned rather than only logged so a test can assert on
     * the decision (skipped vs ingested) instead of scraping log output, which is the difference
     * between testing behaviour and testing a string.
     *
     * @param skipped  true when the corpus was already populated and no reload was forced
     * @param existing how many rows were in {@code kb_chunks} before this run
     * @param ingested how many rows this run wrote (0 when skipped)
     */
    public record LoadReport(boolean skipped, long existing, int ingested) {
    }

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    /**
     * Runs the ingestion pass, honouring the skip/force rules.
     *
     * <p>Public and separate from {@link #run} so it can be driven directly — by a test, or by the
     * semantic-search demo, which needs a populated corpus and should not have to fake an application
     * startup to get one.
     */
    public LoadReport load() {
        long existing = repository.count();

        if (existing > 0 && !forceReload) {
            log.info("kb corpus load SKIPPED — kb_chunks already holds {} chunks. "
                    + "Set aura.kb.force-reload=true to re-ingest (this wipes and re-embeds, "
                    + "and re-embedding is billable).", existing);
            return new LoadReport(true, existing, 0);
        }

        List<Chunk> chunks = readAndChunkCorpus();
        if (chunks.isEmpty()) {
            // Not an exception. An empty corpus directory is a plausible mistake (wrong working
            // directory, a `dir` pointing somewhere else), and the useful response is a loud log plus
            // an untouched database — NOT wiping a populated table and replacing it with nothing.
            log.warn("kb corpus load found no chunkable markdown under {} — nothing ingested, "
                    + "existing corpus left untouched", corpusDir.toAbsolutePath());
            return new LoadReport(false, existing, 0);
        }

        if (existing > 0) {
            log.warn("kb corpus force-reload — deleting {} existing chunks before re-ingesting {}",
                    existing, chunks.size());
            // deleteAllInBatch, not deleteAll: one DELETE statement rather than a SELECT of every row
            // followed by a remove() each. Nothing here needs entity lifecycle callbacks or cascades.
            repository.deleteAllInBatch();
        }

        String model = voyageProps.documentModel();
        int ingested = 0;
        for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
            List<Chunk> batch = chunks.subList(start, Math.min(chunks.size(), start + BATCH_SIZE));

            // embeddingInput(), never breadcrumb + "\n" + text inlined here. That concatenation has
            // exactly one definition (on Chunk) precisely so ingestion and query time cannot drift
            // apart — a difference of one character between the two silently shifts every score.
            List<float[]> vectors = voyage.embedDocuments(batch.stream().map(Chunk::embeddingInput).toList());
            if (vectors.size() != batch.size()) {
                // The client already enforces this, so reaching it means the contract broke upstream.
                // Checked again rather than trusted because the failure it prevents — every chunk
                // paired with its neighbour's vector — is invisible: the corpus loads, the queries
                // run, and every citation points at the wrong passage.
                throw new IllegalStateException("embedding batch returned " + vectors.size()
                        + " vectors for " + batch.size() + " chunks");
            }

            List<KbChunk> rows = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                Chunk chunk = batch.get(i);
                rows.add(new KbChunk(
                        UUID.randomUUID(),
                        chunk.sourceDoc(),
                        chunk.position(),
                        chunk.breadcrumb(),
                        chunk.text(),
                        estimateTokens(chunk.embeddingInput()),
                        vectors.get(i),
                        // Stamped from the SAME config the embedding call above was routed by, in the
                        // same iteration — not from a constant and not re-read later. That is what
                        // makes the stored model name a fact about this row rather than a guess about
                        // the deployment.
                        model));
            }
            repository.saveAll(rows);
            ingested += rows.size();
            log.info("kb corpus load — {}/{} chunks embedded and stored", ingested, chunks.size());
        }

        log.info("kb corpus load COMPLETE — {} chunks from {} stored with embedding_model={}",
                ingested, corpusDir, model);
        return new LoadReport(false, existing, ingested);
    }

    private List<Chunk> readAndChunkCorpus() {
        List<Chunk> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(corpusDir)) {
            // Sorted, so the corpus is ingested in a stable order across machines and runs. It does
            // not affect retrieval, but it makes two ingestion runs comparable, which matters the
            // first time someone diffs them.
            files.filter(path -> path.toString().endsWith(".md"))
                    .sorted()
                    .forEach(path -> chunks.addAll(chunker.chunk(read(path), path.getFileName().toString())));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "cannot read the knowledge-base corpus at " + corpusDir.toAbsolutePath()
                            + " — aura.kb.dir is resolved against the working directory", e);
        }
        return chunks;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read corpus file " + path, e);
        }
    }

    // Ceiling division: a 1-character chunk is one token, not zero. Rounding down would be the more
    // natural-looking integer division and would report a free chunk.
    private static int estimateTokens(String text) {
        return Math.ceilDiv(text.length(), CHARS_PER_TOKEN);
    }
}
