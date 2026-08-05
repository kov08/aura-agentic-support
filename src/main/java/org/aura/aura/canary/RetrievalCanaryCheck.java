package org.aura.aura.canary;

import lombok.extern.slf4j.Slf4j;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.CanaryProperties;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.KbChunk;
import org.aura.aura.util.VectorLiterals;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The startup tripwire for the embedding SPACE, standing beside {@code EmbeddingDimensionCheck}'s
 * tripwire for the embedding WIDTH — same fail-fast class, different question.
 *
 * <p>The width check asks "are the vectors the right shape?" and can answer it from the catalog. This
 * asks "are the vectors still comparable to the ones we are about to compare them with?", and nothing
 * in the database or the configuration can answer that, because a mismatched space is internally
 * consistent at every layer. The only way to find out is to measure.
 *
 * <h2>What it measures, and why exactly this</h2>
 * One stored chunk, re-embedded through the {@code voyage-4-lite}/{@code query} lane, compared by
 * Postgres against its stored {@code voyage-4-large}/{@code document} vector using {@code <=>}.
 *
 * <p>Every element of that sentence is the production path rather than a stand-in for it. The same
 * two models, in the same two lanes, on the same operator, over the same table — one chunk wide. A
 * cheaper check (assert the models' names, assert the row's {@code embedding_model} column) would
 * verify our own configuration against itself, which is exactly the check that passes while the
 * geometry underneath it has moved.
 *
 * <h2>Why a bean lifecycle callback rather than a Flyway callback</h2>
 * {@code EmbeddingDimensionCheck} rides {@code AFTER_MIGRATE} because it inspects the schema Flyway
 * has just written and needs no other bean. This one needs a Voyage client, a repository, and an
 * EntityManagerFactory, none of which exist during migration. {@link SmartInitializingSingleton} runs
 * after every singleton is constructed and still inside context refresh — so the failure lands before
 * the web server starts accepting traffic, which is what "refuses to boot" has to mean to be worth
 * anything.
 *
 * <h2>The empty-corpus exemption</h2>
 * An empty {@code kb_chunks} SKIPS the check with a warning instead of failing it, and the reason is
 * mechanical rather than lenient: the corpus is ingested by {@code KbCorpusLoader}, which runs during
 * a boot. Failing a boot on "no corpus yet" would make the ingestion run impossible to start —
 * nothing could ever populate the table it is complaining about.
 *
 * <p>A POPULATED corpus that does not contain the canary row is a different fact and does fail. That
 * means the corpus was ingested under chunk boundaries this canary's identity no longer matches,
 * which is real drift between the configuration and the data, and it is precisely what a guard is for.
 */
@Slf4j
@Component
// Absent means off, so the many contexts with no database never construct this bean and never need a
// ChunkRepository — the same mechanism that keeps KbCorpusLoader out of them. application.yml turns
// it on for real runs; application-test.yml turns it back off, with the tradeoff written out there.
@ConditionalOnProperty(name = "aura.canary.enabled", havingValue = "true")
public class RetrievalCanaryCheck implements SmartInitializingSingleton {

    private final ChunkRepository chunks;
    private final VoyageEmbeddingClient voyage;
    private final CanaryProperties props;
    private final VoyageProperties voyageProps;

    public RetrievalCanaryCheck(ChunkRepository chunks, VoyageEmbeddingClient voyage,
                                CanaryProperties props, VoyageProperties voyageProps) {
        this.chunks = chunks;
        this.voyage = voyage;
        this.props = props;
        this.voyageProps = voyageProps;
    }

    /**
     * What one canary run observed. Returned rather than only logged so a test can assert on the
     * measurement instead of scraping log output — the same reason {@code KbCorpusLoader.LoadReport}
     * exists.
     *
     * @param skipped     true when there was no claim to verify (an un-ingested corpus)
     * @param distance    the measured query-lane distance, absent when skipped
     * @param storeProbe  the document-lane self-diagnosis distance, absent unless the probe is on
     */
    public record CanaryReading(boolean skipped, OptionalDouble distance, OptionalDouble storeProbe) {
    }

    @Override
    public void afterSingletonsInstantiated() {
        run();
    }

    /**
     * Public and separate from the lifecycle hook so a test can drive it directly, without faking an
     * application startup to find out what it does.
     *
     * @throws IllegalStateException when the measured distance falls outside the configured band, or
     *         when a populated corpus does not contain the canary row
     */
    public CanaryReading run() {
        Optional<KbChunk> found = chunks.findBySourceDocAndChunkIndex(props.sourceDoc(), props.chunkIndex());

        if (found.isEmpty()) {
            if (chunks.count() == 0) {
                log.warn("retrieval canary SKIPPED — kb_chunks is empty, so there is no stored vector "
                        + "to probe. Ingest the corpus (mvn spring-boot:run -Daura.kb.load=true) and "
                        + "this check starts guarding on the next boot.");
                return new CanaryReading(true, OptionalDouble.empty(), OptionalDouble.empty());
            }
            throw new IllegalStateException(
                    "retrieval canary cannot find its chunk: aura.canary.source-doc=" + props.sourceDoc()
                            + ", aura.canary.chunk-index=" + props.chunkIndex() + ", but kb_chunks holds "
                            + chunks.count() + " chunks. A populated corpus that does not contain the "
                            + "canary row means the corpus was ingested under different chunk boundaries "
                            + "than this canary was configured against — re-point aura.canary.* at a "
                            + "chunk that exists AND re-measure the band (CanaryBandHarnessIT), because "
                            + "the band belongs to the text, not to the position.");
        }

        KbChunk canary = found.get();

        // embeddingInput(), never a hand-rolled concatenation: this must be the byte-identical string
        // the document lane embedded at ingestion. A one-character difference here would shift the
        // measured distance for a reason that has nothing to do with the embedding lanes, and the
        // band — measured on the correct string — would then reject a perfectly healthy system.
        float[] fresh = voyage.embedQuery(canary.embeddingInput());
        double distance = measure(canary, fresh);

        // BEFORE the band check, not after, and the order is the whole usability of the probe. It is
        // switched on precisely because the canary is tripping — so if it ran after the throw it
        // would never run at all on the boot that needed it, and the flag would be a diagnostic that
        // only works when there is nothing to diagnose.
        OptionalDouble storeProbe = runStoreProbe(canary);

        if (!props.band().contains(distance)) {
            // EVERY value in this message is OBSERVED, not asserted. The lane comes from
            // voyage.queryInputType() — the same expression embedQuery just routed through — rather
            // than from the word "query" in a format string.
            //
            // That distinction was earned. The first version of this message hard-coded "/query", and
            // the Day 14 lane-flip drill flipped embedQuery's input_type to DOCUMENT: the guard
            // correctly refused the boot AND confidently reported the lane it believed it had used,
            // exonerating the actual cause. An operator following its likely-causes list would have
            // checked the model config and the re-ingestion history, found both correct, and never
            // suspected input_type — because the message told them the lane was fine. A diagnostic
            // that states an intention is worse than no diagnostic; it sends people the wrong way.
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "retrieval canary OUT OF BAND: observed distance %.8f, healthy band %s. "
                            + "The pairing under test is STORED %s/%s (kb_chunks %s#%d) vs FRESH %s/%s "
                            + "(via VoyageEmbeddingClient.embedQuery). "
                            + "These two vectors are supposed to sit in one shared embedding space, and "
                            + "this measurement says they no longer do — so every similarity score this "
                            + "application produces is meaningless, while nothing else would throw. "
                            + "Likely causes, in order: the query lane's input_type was changed (compare "
                            + "the FRESH lane above against the STORED one — if they now match, that is "
                            + "the bug, and the distance will have collapsed toward zero); "
                            + "voyage.query-model or voyage.document-model was changed without "
                            + "re-embedding the corpus; the corpus was re-ingested under a different "
                            + "model; or the provider changed a model behind a stable name. "
                            + "Set aura.canary.store-probe.enabled=true to learn which SIDE moved. Do not "
                            + "widen the band to make this pass — re-measure it with CanaryBandHarnessIT "
                            + "once the cause is understood.",
                    distance, props.band(),
                    // STORED side: the model is read off the ROW (provenance, not configuration), and
                    // the lane is the document lane by definition of how ingestion writes.
                    canary.getEmbeddingModel(), voyage.documentInputType().wireValue(),
                    canary.getSourceDoc(), canary.getChunkIndex(),
                    // FRESH side: both values as the client actually used them a few lines above.
                    voyageProps.queryModel(), voyage.queryInputType().wireValue()));
        }

        // In band: ONE line, at INFO, carrying the observed value. Logging the number on every healthy
        // boot costs nothing and turns the guard into a free time series — the band can be re-derived
        // from a month of boots without running the harness again, and a distance drifting steadily
        // toward an edge becomes visible well before it crosses one.
        log.info("retrieval canary OK — distance={} within band {} ({}/{} {}#{} vs {}/{})",
                String.format(Locale.ROOT, "%.8f", distance), props.band(),
                canary.getEmbeddingModel(), voyage.documentInputType().wireValue(),
                canary.getSourceDoc(), canary.getChunkIndex(),
                voyageProps.queryModel(), voyage.queryInputType().wireValue());

        return new CanaryReading(false, OptionalDouble.of(distance), storeProbe);
    }

    /**
     * The STORE-SIDE probe: fresh {@code document}-lane embedding versus the stored
     * {@code document}-lane vector, same model on both sides.
     *
     * <h2>Why it reports and does not gate</h2>
     * It deliberately does NOT compare against {@code aura.canary.band} and cannot fail the boot. The
     * band was measured for a specific pairing — large/document against lite/query — and a band is a
     * property of the pair, not a universal tolerance. Judging a document-vs-document measurement
     * against it would be comparing a number to a threshold calibrated for a different quantity, and
     * the answer would be noise wearing a verdict's clothes.
     *
     * <p>Its job is attribution, not admission. When the query-lane canary trips, this number says
     * which side moved: near zero means the stored vectors and the document model still agree, so the
     * change is on the query lane; clearly non-zero means the store itself has gone stale and the
     * corpus needs re-embedding. One gate, one band; this is the diagnostic beside it.
     *
     * @return empty when the flag is off — and empty means NOTHING happened: no premium-model call was
     *         made, which is the point of the flag
     */
    OptionalDouble runStoreProbe(KbChunk canary) {
        if (!props.storeProbe().enabled()) return OptionalDouble.empty();

        // embedDocuments (plural, document lane) — the deliberate lane flip that makes this probe a
        // different measurement from the one above. Batch of one, because the API is a batch API.
        float[] fresh = voyage.embedDocuments(java.util.List.of(canary.embeddingInput())).getFirst();
        double distance = measure(canary, fresh);

        log.info("retrieval canary STORE PROBE — distance={} ({}/{} stored vs {}/{} fresh). "
                        + "Near zero means the store side is intact and a canary trip came from the query "
                        + "lane; clearly non-zero means the stored vectors are stale and the corpus needs "
                        + "re-embedding. This probe never fails the boot — the configured band belongs to "
                        + "the large/document vs lite/query pairing and does not apply here.",
                String.format(Locale.ROOT, "%.8f", distance),
                canary.getEmbeddingModel(), voyage.documentInputType().wireValue(),
                voyageProps.documentModel(), voyage.documentInputType().wireValue());

        return OptionalDouble.of(distance);
    }

    // Measured by POSTGRES, with the operator the ranked search uses. See ChunkRepository.distanceFrom:
    // a band calibrated on one arithmetic and enforced on another is calibrated on nothing.
    private double measure(KbChunk canary, float[] fresh) {
        return chunks.distanceFrom(canary.getSourceDoc(), canary.getChunkIndex(),
                        VectorLiterals.toLiteral(fresh))
                .orElseThrow(() -> new IllegalStateException(
                        "the canary row disappeared between lookup and measurement — "
                                + canary.getSourceDoc() + "#" + canary.getChunkIndex()));
    }
}
