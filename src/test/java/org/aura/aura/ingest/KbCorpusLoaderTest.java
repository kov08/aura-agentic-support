package org.aura.aura.ingest;

import org.aura.aura.chunker.DocumentChunker;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.KbChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * The loader's DECISION, tested with no database and no network: given a corpus that may or may not
 * already be loaded, does it ingest, skip, or wipe-and-re-ingest?
 *
 * <p>The Voyage client is mocked because the thing under test is a policy, not an embedding. Calling
 * the real one here would make a unit test billable and would make it fail when a provider has a bad
 * afternoon — for a code path where the vectors' CONTENT is irrelevant and only their COUNT matters.
 */
class KbCorpusLoaderTest {

    private static final int DIM = KbChunk.EMBEDDING_DIMENSION;

    @TempDir Path corpus;

    private ChunkRepository repository;
    private VoyageEmbeddingClient voyage;
    private DocumentChunker chunker;
    private VoyageProperties props;

    @BeforeEach
    void setUp() {
        repository = mock(ChunkRepository.class);
        voyage = mock(VoyageEmbeddingClient.class);
        // The REAL chunker — it is pure logic with no I/O, so mocking it would replace tested
        // behaviour with an assumption about its output shape for no isolation benefit.
        props = new VoyageProperties("test-key", null, "voyage-4-large", "voyage-4-lite",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 2000, 300);
        chunker = new DocumentChunker(props);

        // One vector per input, of the right width. Content is irrelevant here; count is the only
        // property the loader depends on, and it is the one that would misalign chunks and vectors.
        when(voyage.embedDocuments(anyList()))
                .thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(input -> new float[DIM]).toList());
    }

    @Test
    void skipsWhenTheCorpusIsAlreadyLoaded() throws IOException {
        writeDoc("refund-policy.md");
        when(repository.count()).thenReturn(42L);

        KbCorpusLoader.LoadReport report = loader(false).load();

        assertThat(report.skipped()).isTrue();
        assertThat(report.existing()).isEqualTo(42L);
        assertThat(report.ingested()).isZero();

        // The assertion that costs money if it regresses: skipping must happen BEFORE the embedding
        // call, not after. A loader that embeds the whole corpus and then decides not to store it has
        // already spent the money.
        verifyNoInteractions(voyage);
        verify(repository, never()).saveAll(anyList());
        verify(repository, never()).deleteAllInBatch();
    }

    @Test
    void forceReloadWipesTheExistingCorpusAndReIngests() throws IOException {
        writeDoc("refund-policy.md");
        when(repository.count()).thenReturn(42L);

        KbCorpusLoader.LoadReport report = loader(true).load();

        assertThat(report.skipped()).isFalse();
        assertThat(report.ingested()).isPositive();

        verify(repository).deleteAllInBatch();
        verify(repository).saveAll(anyList());
        verify(voyage).embedDocuments(anyList());
    }

    @Test
    void ingestsIntoAnEmptyCorpusWithoutDeletingAnything() throws IOException {
        writeDoc("shipping-policy.md");
        when(repository.count()).thenReturn(0L);

        KbCorpusLoader.LoadReport report = loader(false).load();

        assertThat(report.skipped()).isFalse();
        assertThat(report.existing()).isZero();
        assertThat(report.ingested()).isPositive();
        // No rows to wipe means no DELETE — issuing one anyway would be harmless today and exactly the
        // kind of "harmless" that becomes a data-loss incident once something else writes this table.
        verify(repository, never()).deleteAllInBatch();
    }

    @Test
    void stampsEveryRowWithTheDocumentModelFromConfiguration() throws IOException {
        writeDoc("warranty-policy.md");
        when(repository.count()).thenReturn(0L);

        loader(false).load();

        @SuppressWarnings("unchecked")
        var saved = org.mockito.ArgumentCaptor.forClass((Class<List<KbChunk>>) (Class<?>) List.class);
        verify(repository).saveAll(saved.capture());

        assertThat(saved.getValue()).isNotEmpty().allSatisfy(row -> {
            // The DOCUMENT model, not the query model. Getting this backwards stores a truthful-looking
            // provenance string that names the wrong lane, which is worse than storing nothing: it
            // would make a future cross-era audit confidently clear a corpus that is actually stale.
            assertThat(row.getEmbeddingModel()).isEqualTo("voyage-4-large");
            assertThat(row.getEmbedding()).hasSize(DIM);
            assertThat(row.getTokenCount()).isPositive();
            assertThat(row.getSourceDoc()).isEqualTo("warranty-policy.md");
        });
    }

    @Test
    void anEmptyCorpusDirectoryLeavesAPopulatedTableUntouched() {
        // Nothing written to @TempDir: the "wrong working directory" mistake, which is the realistic
        // way this happens. A force-reload that wipes first and discovers the emptiness afterwards
        // would destroy the corpus in response to a typo.
        when(repository.count()).thenReturn(42L);

        KbCorpusLoader.LoadReport report = loader(true).load();

        assertThat(report.ingested()).isZero();
        verify(repository, never()).deleteAllInBatch();
        verify(repository, never()).saveAll(anyList());
        verifyNoInteractions(voyage);
    }

    @Test
    void nonMarkdownFilesAreIgnored() throws IOException {
        Files.writeString(corpus.resolve("notes.txt"), "# Not markdown by extension\n\nBody text.\n");
        Files.writeString(corpus.resolve("data.json"), "{\"policy\": \"nope\"}");
        when(repository.count()).thenReturn(0L);

        assertThat(loader(false).load().ingested()).isZero();
        verifyNoInteractions(voyage);
    }

    // ---------------------------------------------------------------- fixtures

    private KbCorpusLoader loader(boolean forceReload) {
        return new KbCorpusLoader(chunker, voyage, repository, props, corpus.toString(), forceReload);
    }

    private void writeDoc(String name) throws IOException {
        Files.writeString(corpus.resolve(name), """
                # Refund Policy

                Intro prose that belongs to the document root section.

                ## Standard Refund Window

                Items may be returned within 30 days of delivery for a full refund.

                ## International Orders

                Return shipping for international orders is the customer's responsibility.
                """);
    }
}
