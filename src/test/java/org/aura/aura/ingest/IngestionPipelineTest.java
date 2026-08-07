package org.aura.aura.ingest;

import org.aura.aura.canary.CanaryDocument;
import org.aura.aura.chunker.DocumentChunker;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.client.VoyageTransientException;
import org.aura.aura.config.EmbeddingProperties;
import org.aura.aura.config.IngestionProperties;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.store.CanaryProbeRepository;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.DocumentRepository;
import org.aura.aura.store.KbChunk;
import org.aura.aura.store.KbDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The pipeline's DECISIONS, with no database and no network: given a corpus and a ledger, what does
 * it embed, what does it write, what does it refuse, and what does it do when one document fails?
 *
 * <h2>Why the repositories are stateful fakes rather than plain stubs</h2>
 * The central claim of this component — run it twice, the second run costs nothing — is only
 * meaningful if the second run can SEE what the first one wrote. A stub that returns a fixed list
 * cannot express that, so the two repositories are backed by maps: {@code save} puts, {@code findAll}
 * reads back. They are still Mockito mocks, which is what keeps {@code verify(..., never())}
 * available for the "and it wrote nothing" half of the same claim.
 *
 * <p>The chunker is REAL. It is pure logic with no I/O, so mocking it would replace tested behaviour
 * with an assumption about its output shape and buy no isolation.
 */
class IngestionPipelineTest {

    private static final int DIM = KbChunk.EMBEDDING_DIMENSION;
    private static final String DOCUMENT_MODEL = "voyage-4-large";

    @TempDir Path corpus;

    private ChunkRepository chunks;
    private DocumentRepository documents;
    private CanaryProbeRepository probe;
    private VoyageEmbeddingClient voyage;
    private DocumentChunker chunker;
    private VoyageProperties voyageProps;

    /** The fake ledger: path -> row, shared by every stubbed DocumentRepository method. */
    private final Map<String, KbDocument> ledger = new LinkedHashMap<>();

    /** Every chunk the pipeline has written, in write order. */
    private final List<KbChunk> written = new ArrayList<>();

    @BeforeEach
    void setUp() {
        chunks = mock(ChunkRepository.class);
        documents = mock(DocumentRepository.class);
        probe = mock(CanaryProbeRepository.class);
        voyage = mock(VoyageEmbeddingClient.class);
        voyageProps = new VoyageProperties("test-key", null, DOCUMENT_MODEL, "voyage-4-lite",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 2000, 300);
        chunker = new DocumentChunker(voyageProps);

        wireLedger();
        wireChunkStore();
        embeddingSucceeds();
    }

    // ---------------------------------------------------------------- DoD 3: idempotency

    @Test
    void aSecondRunOverAnUnchangedCorpusMakesNoEmbeddingCallsAndWritesNoChunks() throws IOException {
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");
        writeDoc("shipping-policy.md", "Standard delivery takes three to five days.");

        IngestReport first = pipeline().ingest();

        // Three documents, not two: the synthetic canary is ingested alongside the corpus, by the
        // same plan and the same fingerprint rule as any file.
        assertThat(first.added()).isEqualTo(3);
        assertThat(first.embeddingCalls()).isEqualTo(3);
        assertThat(first.isClean()).isTrue();
        assertThat(ledger).containsOnlyKeys("refund-policy.md", "shipping-policy.md", CanaryDocument.PATH);

        clearInvocations(voyage, chunks, documents);

        IngestReport second = pipeline().ingest();

        // THE IDEMPOTENCY PROOF, and it is one integer. Every other assertion in this method is
        // corroboration; this is the claim.
        assertThat(second.embeddingCalls())
                .as("an unchanged corpus must cost ZERO embedding calls on re-run")
                .isZero();
        assertThat(second.unchanged()).isEqualTo(3);
        assertThat(second.added()).isZero();
        assertThat(second.changed()).isZero();
        assertThat(second.deleted()).isZero();

        // The assertion that costs money if it regresses: the decision has to be made BEFORE the
        // provider is called, not after. A pipeline that embeds everything and then notices nothing
        // changed has already spent the money.
        verifyNoInteractions(voyage);
        verify(chunks, never()).saveAll(anyList());
        verify(chunks, never()).deleteByDocumentId(any());
    }

    @Test
    void editingOneDocumentRebuildsOnlyThatDocument() throws IOException {
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");
        writeDoc("shipping-policy.md", "Standard delivery takes three to five days.");
        pipeline().ingest();

        UUID shippingIdBefore = ledger.get("shipping-policy.md").getId();
        String shippingFingerprintBefore = ledger.get("shipping-policy.md").getFingerprint();
        clearInvocations(voyage, chunks);

        writeDoc("refund-policy.md", "Items may be returned within 45 days.");
        IngestReport report = pipeline().ingest();

        assertThat(report.changed()).isEqualTo(1);
        assertThat(report.unchanged())
                .as("the untouched policy and the canary must both stay free")
                .isEqualTo(2);
        assertThat(report.embeddingCalls())
                .as("exactly one document was rebuilt, and it fits one batch")
                .isEqualTo(1);

        // The document row is UPDATED, not replaced: a new uuid on every edit would break anything
        // holding a reference and would defeat the FK's cascade semantics.
        assertThat(ledger.get("refund-policy.md").getFingerprint()).isNotBlank();
        assertThat(ledger.get("shipping-policy.md").getId()).isEqualTo(shippingIdBefore);
        assertThat(ledger.get("shipping-policy.md").getFingerprint()).isEqualTo(shippingFingerprintBefore);

        // Old chunks are removed before new ones land — without it the swap collides with
        // UNIQUE (source_doc, chunk_index) on every re-ingestion of a changed document.
        verify(chunks).deleteByDocumentId(ledger.get("refund-policy.md").getId());
    }

    @Test
    void aChangeToTheEmbeddingModelRebuildsTheWholeCorpusWithoutAnyFileChanging() throws IOException {
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");
        pipeline().ingest();
        clearInvocations(voyage);

        // Not a file edit — a CONFIG change. A content-only fingerprint would report "unchanged" here
        // and leave the store holding vectors from a model the query lane no longer shares a space
        // with: the Day 12 cross-model failure, arriving with no signal at all.
        VoyageProperties reModelled = new VoyageProperties("test-key", null, "voyage-4-lite",
                "voyage-4-lite", Duration.ofSeconds(2), Duration.ofSeconds(5), 2000, 300);
        IngestReport report = pipeline(reModelled, new EmbeddingProperties(DIM), false).ingest();

        assertThat(report.changed())
                .as("every document is stale when the model that produced its vectors changes")
                .isEqualTo(2);
        assertThat(report.embeddingCalls()).isEqualTo(2);
    }

    @Test
    void deletingAFileRemovesItsDocumentAndItsChunks() throws IOException {
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");
        writeDoc("shipping-policy.md", "Standard delivery takes three to five days.");
        writeDoc("warranty-policy.md", "Hardware carries a one-year warranty.");
        pipeline().ingest();
        UUID removedId = ledger.get("warranty-policy.md").getId();

        Files.delete(corpus.resolve("warranty-policy.md"));
        IngestReport report = pipeline().ingest();

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(report.embeddingCalls()).isZero();
        assertThat(ledger).doesNotContainKey("warranty-policy.md");
        // Explicitly, even though ON DELETE CASCADE would also do it. The two layers are the point:
        // one can be broken by a refactor of the pipeline, the other by a schema rebuilt without the
        // constraint, and they cannot both be broken by the same mistake.
        verify(chunks).deleteByDocumentId(removedId);
    }

    // ---------------------------------------------------------------- DoD 4: failure isolation

    @Test
    void oneDocumentsEmbeddingFailureLeavesTheOthersCommittedAndItselfPending() throws IOException {
        writeDoc("a-policy.md", "Alpha content.");
        writeDoc("b-policy.md", "Bravo content.");
        writeDoc("c-policy.md", "Charlie content.");
        pipeline().ingest();

        // All three become CHANGED, so all three have a fingerprint to fail to advance. Seeding the
        // ledger with stale hashes is what makes "B's fingerprint was not advanced" a real assertion
        // rather than "B was never written in the first place".
        Map<String, String> before = new LinkedHashMap<>();
        ledger.forEach((path, doc) -> {
            doc.advanceTo("stale-" + path, DOCUMENT_MODEL, DocumentChunker.CHUNKER_VERSION);
            before.put(path, doc.getFingerprint());
        });
        written.clear();

        // B's batch throws. Matched on the text so the failure follows the document rather than a
        // call ordinal — reordering the loop must not silently move which document fails.
        when(voyage.embedBatched(anyList())).thenAnswer(call -> {
            List<String> inputs = call.getArgument(0);
            if (inputs.stream().anyMatch(input -> input.contains("Bravo"))) {
                throw new VoyageTransientException("Voyage transient failure: HTTP 503 on " + DOCUMENT_MODEL);
            }
            return vectorsFor(inputs);
        });

        IngestReport report = pipeline().ingest();

        assertThat(report.failed())
                .extracting(IngestReport.Failure::path)
                .as("exactly the failing document, and no other")
                .containsExactly("b-policy.md");
        assertThat(report.failed().getFirst().reason())
                .as("the reason names the exception type, so transient-vs-permanent is readable")
                .contains("VoyageTransientException")
                .contains("503");

        assertThat(ledger.get("a-policy.md").getFingerprint()).isNotEqualTo(before.get("a-policy.md"));
        assertThat(ledger.get("c-policy.md").getFingerprint()).isNotEqualTo(before.get("c-policy.md"));
        assertThat(written)
                .as("A and C committed their chunks despite B failing between them")
                .extracting(KbChunk::getSourceDoc)
                .contains("a-policy.md", "c-policy.md")
                .doesNotContain("b-policy.md");

        // THE assertion. The fingerprint is the pipeline's only record that a document's work is
        // done, so advancing it for a document that failed would make every later run skip it — a
        // permanently missing document that no report ever mentions again.
        assertThat(ledger.get("b-policy.md").getFingerprint())
                .as("a failed document stays pending, so the next run retries it")
                .isEqualTo(before.get("b-policy.md"));
    }

    @Test
    void aFailedDocumentIsRetriedOnTheNextRun() throws IOException {
        writeDoc("a-policy.md", "Alpha content.");
        writeDoc("b-policy.md", "Bravo content.");

        when(voyage.embedBatched(anyList())).thenAnswer(call -> {
            List<String> inputs = call.getArgument(0);
            if (inputs.stream().anyMatch(input -> input.contains("Bravo"))) {
                throw new VoyageTransientException("Voyage is having a bad minute");
            }
            return vectorsFor(inputs);
        });
        assertThat(pipeline().ingest().failed()).hasSize(1);

        // Doing nothing is the recovery. B is still "new" because its fingerprint was never written,
        // so the next run picks it up with no retry queue, no dead-letter table and no state.
        embeddingSucceeds();
        IngestReport recovery = pipeline().ingest();

        assertThat(recovery.isClean()).isTrue();
        assertThat(recovery.added()).isEqualTo(1);
        assertThat(recovery.embeddingCalls()).isEqualTo(1);
        assertThat(ledger).containsKey("b-policy.md");
    }

    // ---------------------------------------------------------------- DoD 5: the guards

    @Test
    void anEmptyScanAgainstAPopulatedLedgerRefusesWithoutForce() throws IOException {
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");
        writeDoc("shipping-policy.md", "Standard delivery takes three to five days.");
        pipeline().ingest();
        clearInvocations(voyage, chunks, documents);

        // The realistic shape of this mistake is not "someone deleted every policy", it is a wrong
        // working directory or an unmounted volume — which is why the fixture empties the directory
        // rather than emptying the ledger.
        Files.delete(corpus.resolve("refund-policy.md"));
        Files.delete(corpus.resolve("shipping-policy.md"));

        assertThatThrownBy(() -> pipeline().ingest())
                .isInstanceOf(IngestionRefusedException.class)
                .hasMessageContaining("NO markdown files")
                .hasMessageContaining("aura.ingest.force=true");

        // "Nothing has been written" is part of the contract, so it is asserted rather than assumed:
        // the guard runs before the first embedding call and before the first delete.
        assertThat(ledger).containsOnlyKeys("refund-policy.md", "shipping-policy.md", CanaryDocument.PATH);
        verifyNoInteractions(voyage);
        verify(chunks, never()).deleteByDocumentId(any());
        verify(documents, never()).delete(any());
    }

    @Test
    void forceOverridesTheEmptyScanGuard() throws IOException {
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");
        writeDoc("shipping-policy.md", "Standard delivery takes three to five days.");
        pipeline().ingest();
        Files.delete(corpus.resolve("refund-policy.md"));
        Files.delete(corpus.resolve("shipping-policy.md"));

        IngestReport report = pipeline(voyageProps, new EmbeddingProperties(DIM), true).ingest();

        assertThat(report.deleted()).isEqualTo(2);
        assertThat(ledger)
                .as("the canary survives — it is synthesised, not scanned, so it is never 'missing'")
                .containsOnlyKeys(CanaryDocument.PATH);
    }

    @Test
    void aMissingCorpusDirectoryRefusesEVENWithForce() throws IOException {
        // DECISION 4, and the one place force does not win.
        //
        // The empty-mount drill made the pair look identical: a typo'd host path and an empty bind
        // mount both scan to zero files and both refuse. They stop being identical the moment force
        // enters, because force is a claim about something the operator LOOKED AT — "yes, I mean to
        // delete those documents" — and a path that cannot be opened is what a typo, an unmounted
        // volume and a container without its bind mount all look like. Nobody looked at any of them.
        // Honouring force there lets one wrong character empty the knowledge base while the operator
        // believes they authorised a curation they had reviewed.
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");
        writeDoc("shipping-policy.md", "Standard delivery takes three to five days.");
        pipeline().ingest();
        clearInvocations(voyage, chunks, documents);

        Path missing = corpus.resolve("no-such-corpus-directory");
        IngestionPipeline forced = new IngestionPipeline(chunker, voyage, chunks, documents, probe,
                voyageProps, new EmbeddingProperties(DIM),
                new IngestionProperties(true, missing.toString(), true),   // force = TRUE
                transactionTemplate());

        assertThatThrownBy(forced::ingest)
                .isInstanceOf(IngestionRefusedException.class)
                .hasMessageContaining("DOES NOT EXIST")
                // The message has to say force is inert here, or an operator reads the refusal, adds
                // the flag they were just told about by the OTHER guard, and re-runs into the same
                // wall with no new information.
                .hasMessageContaining("does NOT apply")
                // ...and it has to say what WOULD express the intent, or the only path forward is
                // giving up. mkdir is the affirmative act a typo cannot perform.
                .hasMessageContaining("mkdir -p");

        // STORE INTACT — the contract, asserted rather than assumed. The guard runs before the first
        // embedding call and before the first delete, so force or not, nothing moved.
        assertThat(ledger)
                .containsOnlyKeys("refund-policy.md", "shipping-policy.md", CanaryDocument.PATH);
        verifyNoInteractions(voyage);
        verify(chunks, never()).deleteByDocumentId(any());
        verify(documents, never()).delete(any());
    }

    @Test
    void anExistingButEmptyDirectoryIsTheCaseForceCanStillOverride() throws IOException {
        // The other half of the split, stated as its own test so the asymmetry cannot be "tidied"
        // into one branch later. Someone stood this directory up; that is a deliberate act, and it is
        // enough for force to have a subject. Same zero-file scan, opposite answer.
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");
        pipeline().ingest();
        Files.delete(corpus.resolve("refund-policy.md"));

        IngestReport report = pipeline(voyageProps, new EmbeddingProperties(DIM), true).ingest();

        assertThat(report.deleted()).isEqualTo(1);
        assertThat(ledger).containsOnlyKeys(CanaryDocument.PATH);
    }

    @Test
    void deletingMoreThanHalfTheLedgerRefusesWithoutForce() throws IOException {
        writeDoc("a.md", "Alpha.");
        writeDoc("b.md", "Bravo.");
        writeDoc("c.md", "Charlie.");
        pipeline().ingest();   // ledger now holds a, b, c and the canary — four documents

        // Three of four is 75%, over the locked 50% threshold. One file survives, so the zero-files
        // rule cannot fire: this exercises the RATIO rule specifically, which is why the fixture
        // leaves something behind.
        Files.delete(corpus.resolve("a.md"));
        Files.delete(corpus.resolve("b.md"));
        Files.delete(corpus.resolve("c.md"));
        writeDoc("d.md", "Delta.");

        assertThatThrownBy(() -> pipeline().ingest())
                .isInstanceOf(IngestionRefusedException.class)
                .hasMessageContaining("would delete 3 of 4")
                .hasMessageContaining("50%");
    }

    @Test
    void aFirstRunAgainstAnEmptyLedgerIsNeverRefused() {
        // Nothing on disk AND nothing stored. Both guards are conditioned on a populated ledger
        // precisely so a fresh database with a misconfigured corpus directory reports "nothing to do"
        // instead of failing a boot over data that does not exist.
        IngestReport report = pipeline().ingest();

        assertThat(report.added())
                .as("the canary alone — the corpus directory is empty")
                .isEqualTo(1);
        assertThat(report.deleted()).isZero();
    }

    // ---------------------------------------------------------------- the canary document

    @Test
    void theCanaryGoesToTheProbeTableAndNeverIntoTheRetrievalCorpus() throws IOException {
        // V4's central claim, at unit level: kb_chunks is the retrieval corpus, and a measuring
        // instrument does not belong in the population it measures. CanaryIsolationIT proves the same
        // thing against real Postgres and a real top-k; this proves the WRITE never happens, which is
        // the cheaper and more precise half — a query returning no canary could also mean the canary
        // simply ranked badly.
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");

        pipeline().ingest();

        assertThat(written)
                .as("not one chunk row may carry the canary's path")
                .noneMatch(row -> row.getSourceDoc().equals(CanaryDocument.PATH));

        // ...and it did get written, just somewhere else. Asserting only the absence above would pass
        // just as happily if the canary had stopped being ingested at all.
        verify(probe).upsert(anyString(), eq(DOCUMENT_MODEL));
        assertThat(ledger)
                .as("the ledger row stays — the canary shares the corpus's scan, plan and transaction, "
                        + "and only its write destination differs")
                .containsKey(CanaryDocument.PATH);
    }

    @Test
    void theCanaryIsEmbeddedFromItsFrozenTextRatherThanThroughTheChunker() {
        // The whole reason the canary bypasses DocumentChunker. Run it through the chunker and the
        // breadcrumb becomes "__canary__" (the file-name fallback), the embedding input becomes a
        // different string, and the measured band in application.yml is calibrated against text that
        // no longer exists anywhere.
        pipeline().ingest();

        verify(voyage).embedBatched(List.of(CanaryDocument.fingerprintContent()));
    }

    @Test
    void everyRowIsStampedWithTheDocumentModelAndPointsAtItsParent() throws IOException {
        writeDoc("refund-policy.md", "Items may be returned within 30 days.");

        pipeline().ingest();

        assertThat(written).isNotEmpty().allSatisfy(row -> {
            // The DOCUMENT model, never the query model. Getting this backwards stores a
            // truthful-looking provenance string naming the wrong lane, which is worse than storing
            // nothing: a future cross-era audit would confidently clear a corpus that is stale.
            assertThat(row.getEmbeddingModel()).isEqualTo(DOCUMENT_MODEL);
            assertThat(row.getEmbedding()).hasSize(DIM);
            assertThat(row.getTokenCount()).isPositive();
            assertThat(row.getDocumentId())
                    .as("document_id is NOT NULL in the schema — an unset one fails at flush, far "
                            + "from the code that forgot it")
                    .isEqualTo(ledger.get(row.getSourceDoc()).getId());
        });
    }

    @Test
    void aCommentOnlyDocumentIsRecordedWithZeroChunksInsteadOfBeingRetriedForever() throws IOException {
        // The chunker strips kb/'s HTML-comment maintainer notes, so a file containing nothing else
        // produces no chunks at all. Advancing its fingerprint anyway is what stops it being reported
        // as pending on every future run.
        Files.writeString(corpus.resolve("notes.md"), "<!-- maintainer note, not policy -->\n");

        IngestReport report = pipeline().ingest();

        assertThat(report.added()).isEqualTo(2);            // notes.md and the canary
        assertThat(report.embeddingCalls()).isEqualTo(1);   // the canary only
        assertThat(ledger).containsKey("notes.md");
        assertThat(written).noneMatch(row -> row.getSourceDoc().equals("notes.md"));

        clearInvocations(voyage);
        assertThat(pipeline().ingest().unchanged()).isEqualTo(2);
        verifyNoInteractions(voyage);
    }

    @Test
    void nonMarkdownFilesAreIgnored() throws IOException {
        Files.writeString(corpus.resolve("notes.txt"), "# Not markdown by extension\n\nBody.\n");
        Files.writeString(corpus.resolve("data.json"), "{\"policy\": \"nope\"}");

        assertThat(pipeline().ingest().added())
                .as("the canary, and nothing scanned")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- fixtures

    private IngestionPipeline pipeline() {
        return pipeline(voyageProps, new EmbeddingProperties(DIM), false);
    }

    private IngestionPipeline pipeline(VoyageProperties props, EmbeddingProperties embedding, boolean force) {
        return new IngestionPipeline(chunker, voyage, chunks, documents, probe, props, embedding,
                new IngestionProperties(true, corpus.toString(), force), transactionTemplate());
    }

    /**
     * A TransactionTemplate over a mocked transaction manager: the callbacks run, commit is called,
     * and no database is involved. It is not testing rollback — that is Spring's job, and
     * CanaryIsolationIT exercises it against real Postgres — it is testing that the pipeline's
     * per-document work is INVOKED inside a template at all, which is the thing a self-invoked
     * {@code @Transactional} would silently fail to do.
     */
    private static TransactionTemplate transactionTemplate() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new TransactionTemplate(txManager);
    }

    private void wireLedger() {
        when(documents.findAll()).thenAnswer(call -> new ArrayList<>(ledger.values()));
        when(documents.findByPath(any())).thenAnswer(call ->
                java.util.Optional.ofNullable(ledger.get(call.<String>getArgument(0))));
        when(documents.save(any())).thenAnswer(call -> put(call.getArgument(0)));
        when(documents.saveAndFlush(any())).thenAnswer(call -> put(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> ledger.remove(call.<KbDocument>getArgument(0).getPath()))
                .when(documents).delete(any());
    }

    private KbDocument put(KbDocument document) {
        ledger.put(document.getPath(), document);
        return document;
    }

    private void wireChunkStore() {
        when(chunks.saveAll(anyList())).thenAnswer(call -> {
            List<KbChunk> rows = call.getArgument(0);
            written.addAll(rows);
            return rows;
        });
        when(chunks.deleteByDocumentId(any())).thenAnswer(call -> {
            UUID documentId = call.getArgument(0);
            int before = written.size();
            written.removeIf(row -> row.getDocumentId().equals(documentId));
            return before - written.size();
        });
    }

    /** One vector per input, of the right width. Content is irrelevant; COUNT is what misaligns. */
    private void embeddingSucceeds() {
        when(voyage.embedBatched(anyList()))
                .thenAnswer(call -> vectorsFor(call.getArgument(0)));
    }

    private static VoyageEmbeddingClient.BatchedEmbeddings vectorsFor(List<String> inputs) {
        return new VoyageEmbeddingClient.BatchedEmbeddings(
                inputs.stream().map(input -> new float[DIM]).toList(), 1);
    }

    private void writeDoc(String name, String body) throws IOException {
        Files.writeString(corpus.resolve(name), "# " + name + "\n\n" + body + "\n");
    }
}
