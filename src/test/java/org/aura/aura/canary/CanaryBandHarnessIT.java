package org.aura.aura.canary;

import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.CanaryProperties;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.ingest.KbCorpusLoader;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.KbChunk;
import org.aura.aura.util.VectorLiterals;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK 0 — the measurement that has to happen BEFORE the guard exists.
 *
 * <p>{@code RetrievalCanaryCheck} refuses to boot when a measured distance falls outside a
 * configured band. Where does the band come from? Not from taste. A threshold chosen by intuition and
 * then calibrated against production is not a guard, it is a source of pages — too tight and every
 * deploy trips it, too loose and it never fires at all. So the band is sampled from the real system
 * first, and the guard is written afterwards against a number that already exists.
 *
 * <h2>The pre-registered rule</h2>
 * <pre>
 *   healthy band = [ observed_min - 0.5 x (max - min),  observed_max + 0.5 x (max - min) ]
 * </pre>
 * This rule was written down before any of these numbers were seen, and it is not adjusted after the
 * fact. That is the entire discipline: a rule chosen after looking at the data is a rule fitted to
 * the data, and the honest version of "the band looked too tight so I widened it" is "I had no rule".
 * If a future measurement says this band is wrong, the fix is a new measurement run with the same
 * rule and a config diff — not a nudged constant.
 *
 * <h2>What is actually being sampled</h2>
 * Twenty fresh {@code voyage-4-lite}/{@code query} embeddings of ONE stored chunk's exact embedding
 * input, each compared against that chunk's stored {@code voyage-4-large}/{@code document} vector.
 * The spread across those twenty is the noise floor of the pairing: model-vs-model difference, plus
 * whatever run-to-run variation the provider has. Both are folded in deliberately, because the guard
 * cannot tell them apart either and should not fire on either.
 *
 * <p>The distance is computed by POSTGRES, with the same {@code <=>} the ranked search uses — not in
 * the JVM. A band measured on one arithmetic and enforced on another is a band measured on nothing.
 *
 * <h2>Not a build gate</h2>
 * {@code @Tag("manual")}, like {@code SemanticSearchDemoIT}: it needs a live {@code VOYAGE_API_KEY},
 * it ingests the corpus (billable) and then makes twenty more billable calls, and its output is a
 * pair of numbers a human copies into application.yml. Run it deliberately:
 *
 * <pre>{@code mvn verify -Pdemo -Dit.test=CanaryBandHarnessIT}</pre>
 */
@Tag("manual")
@ActiveProfiles("test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // Opt back in to the database (application-test.yml excludes it for the DB-less majority).
                "spring.autoconfigure.exclude=",
                // Ingest the corpus during context startup — the harness needs a stored document-lane
                // vector to measure against, and that is what ingestion produces.
                "aura.kb.load=true"
        })
@Testcontainers
class CanaryBandHarnessIT {

    /**
     * Twenty. Enough for the spread to stop moving materially with each new sample, few enough that
     * the run is seconds and cents. It is fixed rather than tuned because n is part of the
     * pre-registered rule's provenance: a band from n=3 and a band from n=200 are different claims,
     * and the number is recorded next to the band in application.yml so nobody has to guess which.
     */
    private static final int SAMPLES = 20;

    /**
     * A deliberate pause between samples.
     *
     * <p>Measured, not guessed. Two runs, back-to-back and then at 1.2s intervals, each completed
     * EXACTLY three samples and then took a 429 that outlived all three retry attempts. Three
     * successes per run, twice, at two different rates is not a burst limit — it is a
     * requests-per-MINUTE ceiling of 3 on this key. So the pacing is set just under it (~2.9/min),
     * which puts the run at roughly seven minutes.
     *
     * <p>The alternative — raising {@code resilience4j.retry.instances.voyage.max-attempts} until the
     * harness pushes through — was rejected on principle: that property is production's retry budget
     * for a customer-facing embedding call, and tuning it to suit a once-a-quarter measurement script
     * is the wrong lever on the wrong component. The harness bends; production does not.
     *
     * <p>Worth being precise about what the pause does NOT do: it does not make the samples
     * independent in some statistical sense, and it does not change what is being measured. Twenty
     * identical requests twenty seconds apart and twenty a millisecond apart sample the same thing —
     * the provider's run-to-run variation on one input. It only stops the harness from rate-limiting
     * itself.
     */
    private static final long PACING_MILLIS = 21_000L;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired KbCorpusLoader loader;
    @Autowired ChunkRepository chunks;
    @Autowired VoyageEmbeddingClient voyage;
    @Autowired VoyageProperties voyageProps;
    @Autowired CanaryProperties canaryProps;
    @Autowired JdbcTemplate jdbc;

    @Test
    void measureTheCanaryBand() throws InterruptedException {
        assertThat(voyageProps.apiKey())
                .as("this harness makes REAL Voyage calls — export a real VOYAGE_API_KEY (the 'test' "
                        + "profile falls back to the dummy 'test-key' when it is absent)")
                .isNotEqualTo("test-key");

        // The corpus was ingested during startup by the ApplicationRunner; this call is the skip guard
        // proving it, exactly as in SemanticSearchDemoIT.
        assertThat(loader.load().skipped()).isTrue();

        // ---- 1. The canary chunk, addressed the way the guard will address it -----------------
        KbChunk canary = chunks
                .findBySourceDocAndChunkIndex(canaryProps.sourceDoc(), canaryProps.chunkIndex())
                .orElseThrow(() -> new AssertionError(
                        "no chunk at " + canaryProps.sourceDoc() + "#" + canaryProps.chunkIndex()
                                + " — aura.canary.source-doc/chunk-index must name a chunk the "
                                + "chunker actually produces from kb/"));

        assertThat(canary.getEmbeddingModel())
                .as("the STORED side of the pairing must be the document lane's premium model — "
                        + "measuring against anything else calibrates a band for a pairing no "
                        + "customer request ever rides")
                .isEqualTo(voyageProps.documentModel());

        // embeddingInput(), never a hand-rolled breadcrumb + "\n" + content here: the query lane must
        // embed the byte-identical string the document lane embedded at ingestion, or the measured
        // distance includes a text difference that has nothing to do with the lanes.
        String canaryText = canary.embeddingInput();

        System.out.printf("%n=== Day 14 canary band harness ===%n");
        System.out.printf("stored : %s#%d  model=%s  lane=document%n",
                canary.getSourceDoc(), canary.getChunkIndex(), canary.getEmbeddingModel());
        System.out.printf("fresh  : model=%s  lane=query  n=%d%n",
                voyageProps.queryModel(), SAMPLES);
        System.out.printf("text   : %s%n%n", oneLine(canaryText));

        // ---- 2. Sample -------------------------------------------------------------------------
        List<Double> distances = new ArrayList<>(SAMPLES);
        for (int i = 0; i < SAMPLES; i++) {
            if (i > 0) Thread.sleep(PACING_MILLIS);
            float[] fresh = voyage.embedQuery(canaryText);
            double distance = chunks
                    .distanceFrom(canary.getSourceDoc(), canary.getChunkIndex(),
                            VectorLiterals.toLiteral(fresh))
                    .orElseThrow(() -> new AssertionError("the canary row vanished mid-run"));
            distances.add(distance);
            System.out.printf(Locale.ROOT, "  sample %2d  distance=%.8f%n", i + 1, distance);
        }

        // ---- 3. Summarise ----------------------------------------------------------------------
        double min = distances.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
        double max = distances.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        double mean = distances.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double spread = max - min;

        // THE PRE-REGISTERED RULE. Half the observed spread of headroom on each side: it scales the
        // tolerance to the noise that was actually measured instead of to a number someone liked, and
        // it stays a fixed multiple of that noise rather than a fixed distance, so a quieter pairing
        // gets a tighter band for free.
        double bandMin = min - 0.5 * spread;
        double bandMax = max + 0.5 * spread;

        System.out.printf(Locale.ROOT,
                "%n  n=%d  min=%.8f  max=%.8f  mean=%.8f  spread=%.8f%n",
                SAMPLES, min, max, mean, spread);
        System.out.printf(Locale.ROOT,
                "%n  band = [min - 0.5*spread, max + 0.5*spread] = [%.8f, %.8f]%n", bandMin, bandMax);
        System.out.printf(Locale.ROOT, "%n  paste into application.yml:%n"
                        + "    band:%n"
                        + "      min-distance: %.6f%n"
                        + "      max-distance: %.6f%n%n",
                bandMin, bandMax);

        // ---- 4. Corpus token stats, for the TASK 3 context budget -------------------------------
        // Piggy-backed on this run ON PURPOSE, and it is worth saying why rather than leaving it to
        // look like scope creep. The expensive part of both measurements is identical and billable:
        // a fully ingested corpus. Running a second manual IT for one SELECT would mean embedding
        // every chunk a second time to answer a question this database can already answer.
        Map<String, Object> tokens = jdbc.queryForMap(
                "SELECT count(*) AS n, avg(token_count) AS avg, max(token_count) AS max FROM kb_chunks");
        System.out.printf("=== corpus token_count (aura.retrieval.context-token-budget input) ===%n");
        System.out.printf("  SELECT count(*), avg(token_count), max(token_count) FROM kb_chunks;%n");
        System.out.printf("  n=%s  avg=%s  max=%s%n%n", tokens.get("n"), tokens.get("avg"), tokens.get("max"));

        // Sanity only. The harness MEASURES; it does not have an opinion about what the numbers should
        // be — an assertion on the distance itself would be the invented threshold this whole class
        // exists to avoid.
        assertThat(distances).hasSize(SAMPLES);
        assertThat(distances).allSatisfy(d -> assertThat(d)
                .as("cosine distance lives in [0, 2]; anything else means the operator or the "
                        + "vectors are not what this harness thinks they are")
                .isBetween(0.0, 2.0));
    }

    private static String oneLine(String text) {
        String flat = text.replace("\n", " \\n ");
        return flat.length() <= 120 ? flat : flat.substring(0, 119) + "…";
    }
}
