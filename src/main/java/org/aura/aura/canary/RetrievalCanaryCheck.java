package org.aura.aura.canary;

import lombok.extern.slf4j.Slf4j;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.CanaryProperties;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.store.CanaryProbe;
import org.aura.aura.store.CanaryProbeRepository;
import org.aura.aura.util.VectorLiterals;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
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
 * One stored vector — the single row of {@code canary_probe} — compared by Postgres against a fresh
 * {@code voyage-4-lite}/{@code query} embedding of {@link CanaryDocument}'s frozen text, using
 * {@code <=>}. The stored side was written on the {@code voyage-4-large}/{@code document} lane by the
 * ingestion pipeline, in the same transaction as the canary's ledger row.
 *
 * <h2>Why the probe is not a chunk any more (V4)</h2>
 * It was one, and being one made it a candidate answer to customer questions: the canary's text is a
 * verbatim copy of {@code refund-policy.md} chunk 0, so a refund query matched both and spent part of
 * its context budget twice on one passage. The deeper problem was categorical — {@code kb_chunks} is
 * the retrieval CORPUS, and a measuring instrument does not belong in the population it measures. The
 * vector now lives in its own one-row table, so retrieval cannot reach it even by accident and no
 * future query has to remember to exclude it.
 *
 * <p>Two things fell out of that move. The probe is addressed by a primary key the schema pins to 1
 * rather than by {@code aura.canary.source-doc} / {@code chunk-index}, so those properties are gone —
 * an addressing config that can drift from the data is exactly what this guard exists to catch, and
 * it should not have one of its own. And the text being re-embedded is now read from the constant
 * rather than from a stored row, which is stricter: an edit to the constant without a re-ingest trips
 * the guard instead of silently redefining what it measures.
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
 * <h2>The bootstrapping exemption</h2>
 * An absent probe row SKIPS the check with a warning instead of failing it, and the reason is
 * mechanical rather than lenient. The row is written by
 * {@link org.aura.aura.ingest.IngestionPipeline}, which is an {@code ApplicationRunner} and therefore
 * runs AFTER context refresh — while this check is a {@link SmartInitializingSingleton} and runs
 * during it. So on the boot that would populate the probe, this code necessarily runs first and
 * necessarily finds nothing. Failing there would make that boot die before the runner could execute,
 * and nothing could ever populate the table being complained about.
 *
 * <p>V3 had a second branch here: an absent canary alongside a POPULATED {@code kb_chunks} failed the
 * boot as drift, because the canary was addressed by configuration and a mismatch meant the corpus
 * had been chunked under boundaries that configuration no longer named. V4 deleted that failure mode
 * rather than that branch — the probe is addressed by a primary key the schema pins to 1, so there is
 * no addressing left to drift. The one remaining route to an absent probe with a populated store is a
 * canary document that failed during ingestion, and the pipeline already reports that in
 * {@code IngestReport.failed} and logs it at ERROR.
 */
@Slf4j
@Component
// Absent means off, so the many contexts with no database never construct this bean and never need a
// ChunkRepository — the same mechanism that keeps IngestionPipeline out of them. application.yml turns
// it on for real runs; application-test.yml turns it back off, with the tradeoff written out there.
@ConditionalOnProperty(name = "aura.canary.enabled", havingValue = "true")
public class RetrievalCanaryCheck implements SmartInitializingSingleton {

    private final CanaryProbeRepository probe;
    private final VoyageEmbeddingClient voyage;
    private final CanaryProperties props;
    private final VoyageProperties voyageProps;

    public RetrievalCanaryCheck(CanaryProbeRepository probe, VoyageEmbeddingClient voyage,
                                CanaryProperties props, VoyageProperties voyageProps) {
        this.probe = probe;
        this.voyage = voyage;
        this.props = props;
        this.voyageProps = voyageProps;
    }

    /**
     * What one canary run observed. Returned rather than only logged so a test can assert on the
     * measurement instead of scraping log output — the same reason
     * {@link org.aura.aura.ingest.IngestReport} exists.
     *
     * @param skipped     true when there was no claim to verify (no probe row yet)
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
     * @throws IllegalStateException when the measured distance falls outside the configured band
     */
    public CanaryReading run() {
        Optional<CanaryProbe> found = probe.findProbe();

        if (found.isEmpty()) {
            // THE BOOTSTRAPPING EXEMPTION, and it is mechanical rather than lenient. The probe row is
            // written by IngestionPipeline, which is an ApplicationRunner — it fires AFTER context
            // refresh, and this check is a SmartInitializingSingleton that runs DURING it. So on the
            // boot that would populate the probe, this code necessarily runs first and necessarily
            // finds nothing. Throwing here would make that boot fail before the runner could execute,
            // and nothing could ever populate the table this check is complaining about.
            //
            // V3's version of this rule had a second branch: an absent canary alongside a POPULATED
            // kb_chunks was treated as drift and failed the boot, because the canary was addressed by
            // configuration (aura.canary.source-doc / chunk-index) and a mismatch meant the corpus had
            // been chunked under boundaries that configuration no longer named. That failure mode no
            // longer exists — the probe is addressed by a primary key the schema pins to 1 — so the
            // branch went with it. The one remaining way to reach this state with a populated store is
            // a canary document that FAILED during ingestion, and that is already reported: it lands
            // in IngestReport.failed and is logged at ERROR by the pipeline itself.
            log.warn("retrieval canary SKIPPED — canary_probe holds no row, so there is no stored "
                    + "vector to measure against. This is the expected state on a first boot and "
                    + "immediately after V4; ingest the corpus "
                    + "(mvn spring-boot:run -Daura.ingest.enabled=true) and this check starts guarding "
                    + "on the next boot. If ingestion has already run, check its report for a failed "
                    + "'{}' document.", CanaryDocument.PATH);
            return new CanaryReading(true, OptionalDouble.empty(), OptionalDouble.empty());
        }

        CanaryProbe canary = found.get();

        // The FROZEN CONSTANT, not text read back out of the row — and that is a real change from V3,
        // not just a consequence of the column no longer being there. The probe table stores a vector
        // and no prose, so the text being re-embedded is now unambiguously the same text the
        // fingerprint was computed over and the pipeline embedded. If the constant is edited without
        // a re-ingest, the fingerprint moves, the plan says 'changed', and until that run happens this
        // measurement compares new text against an old vector and trips — which is correct, and is the
        // store telling the truth about being stale.
        String canaryText = CanaryDocument.fingerprintContent();

        float[] fresh = voyage.embedQuery(canaryText);
        double distance = measure(fresh);

        // BEFORE the band check, not after, and the order is the whole usability of the probe. It is
        // switched on precisely because the canary is tripping — so if it ran after the throw it
        // would never run at all on the boot that needed it, and the flag would be a diagnostic that
        // only works when there is nothing to diagnose.
        OptionalDouble storeProbe = runStoreProbe(canaryText);

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
                            + "The pairing under test is STORED %s/%s (canary_probe, text '%s') vs "
                            + "FRESH %s/%s (via VoyageEmbeddingClient.embedQuery). "
                            + "These two vectors are supposed to sit in one shared embedding space, and "
                            + "this measurement says they no longer do — so every similarity score this "
                            + "application produces is meaningless, while nothing else would throw. "
                            + "Likely causes, in order: the query lane's input_type was changed (compare "
                            + "the FRESH lane above against the STORED one — if they now match, that is "
                            + "the bug, and the distance will have collapsed toward zero); "
                            + "voyage.query-model or voyage.document-model was changed without "
                            + "re-embedding the corpus; the corpus was re-ingested under a different "
                            + "model; CanaryDocument's frozen text was edited without a re-ingest; or "
                            + "the provider changed a model behind a stable name. "
                            + "Set aura.canary.store-probe.enabled=true to learn which SIDE moved. Do not "
                            + "widen the band to make this pass — re-measure it with CanaryBandHarnessIT "
                            + "once the cause is understood.",
                    distance, props.band(),
                    // STORED side: the model is read off the ROW (provenance, not configuration), and
                    // the lane is the document lane by definition of how ingestion writes.
                    canary.getEmbeddingModelId(), voyage.documentInputType().wireValue(),
                    CanaryDocument.PATH,
                    // FRESH side: both values as the client actually used them a few lines above.
                    voyageProps.queryModel(), voyage.queryInputType().wireValue()));
        }

        // In band: ONE line, at INFO, carrying the observed value. Logging the number on every healthy
        // boot costs nothing and turns the guard into a free time series — the band can be re-derived
        // from a month of boots without running the harness again, and a distance drifting steadily
        // toward an edge becomes visible well before it crosses one.
        log.info("retrieval canary OK — distance={} within band {} ({}/{} canary_probe '{}' vs {}/{})",
                String.format(Locale.ROOT, "%.8f", distance), props.band(),
                canary.getEmbeddingModelId(), voyage.documentInputType().wireValue(),
                CanaryDocument.PATH,
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
    OptionalDouble runStoreProbe(String canaryText) {
        if (!props.storeProbe().enabled()) return OptionalDouble.empty();

        // embedDocuments (plural, document lane) — the deliberate lane flip that makes this probe a
        // different measurement from the one above. Batch of one, because the API is a batch API.
        float[] fresh = voyage.embedDocuments(List.of(canaryText)).getFirst();
        double distance = measure(fresh);

        log.info("retrieval canary STORE PROBE — distance={} (canary_probe stored vs {}/{} fresh). "
                        + "Near zero means the store side is intact and a canary trip came from the query "
                        + "lane; clearly non-zero means the stored vectors are stale and the corpus needs "
                        + "re-embedding. This probe never fails the boot — the configured band belongs to "
                        + "the large/document vs lite/query pairing and does not apply here.",
                String.format(Locale.ROOT, "%.8f", distance),
                voyageProps.documentModel(), voyage.documentInputType().wireValue());

        return OptionalDouble.of(distance);
    }

    // Measured by POSTGRES, with the operator the ranked search uses. See
    // CanaryProbeRepository.distanceFrom: a band calibrated on one arithmetic and enforced on another
    // is calibrated on nothing.
    private double measure(float[] fresh) {
        return probe.distanceFrom(VectorLiterals.toLiteral(fresh))
                .orElseThrow(() -> new IllegalStateException(
                        "the canary probe row disappeared between lookup and measurement"));
    }
}
