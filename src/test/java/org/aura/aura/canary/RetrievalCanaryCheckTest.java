package org.aura.aura.canary;

import org.aura.aura.client.EmbeddingInputType;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.CanaryProperties;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.store.CanaryProbe;
import org.aura.aura.store.CanaryProbeRepository;
import org.aura.aura.util.VectorLiterals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The canary's LOGIC, with fixed vectors and stubbed distances at the two mocked boundaries.
 *
 * <p>This is where the tradeoff written into application-test.yml gets paid for. The canary is
 * disabled in every test profile, so nothing in CI ever makes the live Voyage call it exists to make
 * — which means the band comparison, the refuse-to-boot path, the skip rule and the probe's on/off
 * behaviour all have to be provable without one. They are, because none of them is about the network:
 * they are about what this class does with a number.
 *
 * <p>The distances are stubbed at the REPOSITORY, which is the honest seam. The arithmetic that
 * produces them is Postgres's {@code <=>}, and pinning Postgres's arithmetic is PgVectorSchemaIT's
 * job — asserting it a second time here with a JVM reimplementation would be testing a copy of the
 * thing rather than the thing.
 *
 * <h2>What V4 changed here</h2>
 * The seam moved from {@code ChunkRepository} to {@link CanaryProbeRepository}, because the canary's
 * vector moved out of the retrieval corpus into its own one-row table. Two consequences show up as
 * test changes rather than as prose:
 *
 * <ul>
 *   <li>The text being embedded is {@link CanaryDocument#fingerprintContent()} — a constant — rather
 *       than something read back off a stored row. There is no longer a stored copy of the prose to
 *       disagree with it.</li>
 *   <li>There used to be TWO absent-row tests: an empty corpus skipped, and a populated corpus
 *       missing the canary row failed as drift. Only the skip survives. The failing case existed
 *       because the canary was addressed by configuration and could be aimed at a chunk that did not
 *       exist; the probe is addressed by a primary key the schema pins to 1, so that state is no
 *       longer reachable.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RetrievalCanaryCheckTest {

    /** The model stamped on the stored probe row — provenance, read off the row, not configuration. */
    private static final String STORED_MODEL = "voyage-4-large";

    // The MEASURED band from application.yml. Using the real numbers rather than round ones keeps the
    // test honest about what it is guarding: these are the values a boot actually compares against.
    private static final double BAND_MIN = 0.242516;
    private static final double BAND_MAX = 0.244003;

    // Fixed, tiny, and DIFFERENT from each other, so the two lanes produce different pgvector literals
    // and each can be stubbed independently — which is what lets a test prove the store probe made its
    // own separate call rather than reusing the query lane's.
    private static final float[] QUERY_VECTOR = {0.1f, 0.2f, 0.3f};
    private static final float[] DOCUMENT_VECTOR = {0.4f, 0.5f, 0.6f};

    @Mock CanaryProbeRepository probe;
    @Mock VoyageEmbeddingClient voyage;

    /**
     * The client reports its own lanes, so a mocked client has to be told what to report. Stubbed
     * {@code lenient()} because the absent-row test never reaches a message or a log line and would
     * otherwise trip strict-stub checking.
     *
     * <p>These are the honest defaults — what the real client returns. The lane-flip test overrides
     * {@code queryInputType} to reproduce the drill.
     */
    @BeforeEach
    void lanesReportThemselves() {
        lenient().when(voyage.queryInputType()).thenReturn(EmbeddingInputType.QUERY);
        lenient().when(voyage.documentInputType()).thenReturn(EmbeddingInputType.DOCUMENT);
    }

    // ---------------------------------------------------------------- the gate

    @Test
    void anInBandDistanceBoots() {
        probeRowExists();
        queryLaneReturns(0.2434);   // mid-band, near the measured mean

        RetrievalCanaryCheck.CanaryReading reading = check(storeProbeOff()).run();

        assertThat(reading.skipped()).isFalse();
        assertThat(reading.distance()).hasValue(0.2434);
        assertThat(reading.storeProbe()).isEmpty();
    }

    @Test
    void aDistanceAboveTheBandRefusesToBootAndNamesBothSidesOfThePairing() {
        probeRowExists();
        queryLaneReturns(0.71);   // the shape of a cross-family mismatch: not an error, just far

        assertThatThrownBy(() -> check(storeProbeOff()).run())
                .isInstanceOf(IllegalStateException.class)
                // The observed value AND the band. "Out of band" alone sends the reader looking;
                // "0.71 against [0.2425, 0.2440]" tells them how badly and in which direction.
                .hasMessageContaining("0.71")
                .hasMessageContaining("0.242516")
                .hasMessageContaining("0.244003")
                // BOTH SIDES of the pairing, because the failure is a relationship between two things
                // and naming only one of them makes the message a riddle. Which model, which lane, on
                // each side — and now which TABLE, since "the stored vector" is no longer a chunk.
                .hasMessageContaining("voyage-4-large").hasMessageContaining("document")
                .hasMessageContaining("voyage-4-lite").hasMessageContaining("query")
                .hasMessageContaining("canary_probe")
                .hasMessageContaining(CanaryDocument.PATH);
    }

    /**
     * THE regression test for the Day 14 lane-flip drill. Flipping {@code embedQuery}'s input_type to
     * DOCUMENT correctly tripped the guard — and the guard then reported {@code voyage-4-lite/query},
     * because the lane was a literal in the format string rather than a value read back from the
     * client. The message exonerated the actual cause: its likely-causes list sent the reader to the
     * model config and the re-ingestion history, both of which were fine.
     *
     * <p>So the assertion is not "the message mentions a lane" but "the message mentions the lane the
     * client REALLY USED". Here the client reports DOCUMENT on both sides — the flipped state — and
     * the message must say so, and must not claim query.
     */
    @Test
    void anOutOfBandMessageReportsTheLaneTheClientActuallySent() {
        probeRowExists();
        when(voyage.queryInputType()).thenReturn(EmbeddingInputType.DOCUMENT);   // the flip
        when(voyage.embedQuery(canaryText())).thenReturn(QUERY_VECTOR);
        when(probe.distanceFrom(eq(VectorLiterals.toLiteral(QUERY_VECTOR))))
                .thenReturn(Optional.of(0.0668));   // the distance the real drill produced

        Throwable thrown = catchThrowable(() -> check(storeProbeOff()).run());

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage())
                .as("the guard must report the lane it sent, and must not claim one it did not")
                .contains("voyage-4-lite/document")
                .doesNotContain("voyage-4-lite/query");
    }

    @Test
    void anOutOfBandMessageNamesTheInputTypeFlipAsACandidateCause() {
        // The other half of the same repair. Reporting the lane honestly is not enough if the reader
        // still has no reason to suspect it — the cause list has to contain the thing the drill broke.
        probeRowExists();
        queryLaneReturns(0.0668);

        assertThatThrownBy(() -> check(storeProbeOff()).run())
                .hasMessageContaining("input_type was changed");
    }

    @Test
    void anOutOfBandMessageNamesAnUnIngestedTextEditAsACandidateCause() {
        // V4 added a cause the V3 message could not have had. The text is now a code constant, so
        // editing CanaryDocument without re-running ingestion leaves a fresh embedding of new prose
        // being measured against a stored vector of the old — a trip whose fix is `mvn spring-boot:run
        // -Daura.ingest.enabled=true`, not anything to do with models or lanes.
        probeRowExists();
        queryLaneReturns(0.71);

        assertThatThrownBy(() -> check(storeProbeOff()).run())
                .hasMessageContaining("frozen text was edited without a re-ingest");
    }

    @Test
    void aDistanceBelowTheBandAlsoRefusesToBoot() {
        // The direction that looks like good news and is not. A distance far BELOW the measured floor
        // means the two lanes suddenly agree more than they ever have — which does not happen because
        // retrieval improved. It happens because both sides became the same model, or the stored
        // vector was overwritten by a query-lane one. A one-sided check would wave that through.
        probeRowExists();
        queryLaneReturns(0.0);

        assertThatThrownBy(() -> check(storeProbeOff()).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OUT OF BAND");
    }

    @Test
    void theBandEdgesAreInclusive() {
        // Stated once, so nobody has to re-derive it from the arithmetic later: the band is a CLOSED
        // interval. It was built by widening the observed range, so the endpoints are values the rule
        // deliberately admits.
        probeRowExists();
        queryLaneReturns(BAND_MIN);
        assertThat(check(storeProbeOff()).run().distance()).hasValue(BAND_MIN);

        queryLaneReturns(BAND_MAX);
        assertThat(check(storeProbeOff()).run().distance()).hasValue(BAND_MAX);
    }

    // ---------------------------------------------------------------- the store-side probe

    @Test
    void theStoreProbeIsCompletelyInertWhenTheFlagIsOff() {
        probeRowExists();
        queryLaneReturns(0.2434);

        RetrievalCanaryCheck.CanaryReading reading = check(storeProbeOff()).run();

        // "Inert" means no premium-model call happened, not merely that its result was discarded.
        // The flag exists to avoid paying for a voyage-4-large embedding on every single boot, so a
        // probe that ran and then threw the number away would defeat its own purpose.
        verify(voyage, never()).embedDocuments(any());
        assertThat(reading.storeProbe()).isEmpty();
    }

    @Test
    void theStoreProbeMeasuresTheDocumentLaneAgainstTheStoredVectorWhenOn() {
        probeRowExists();
        queryLaneReturns(0.2434);
        documentLaneReturns(0.000002);   // same model both sides: near zero, as expected

        RetrievalCanaryCheck.CanaryReading reading = check(storeProbeOn()).run();

        verify(voyage).embedDocuments(List.of(canaryText()));
        assertThat(reading.storeProbe()).hasValue(0.000002);
    }

    @Test
    void theStoreProbeNeverFailsTheBootEvenWhenItsNumberIsOutsideTheQueryLaneBand() {
        // The probe REPORTS, it does not gate — and this is the test that keeps someone from
        // "tidying" it into a second guard. Its number is a document-vs-document measurement; the
        // band was measured for large/document against lite/query. Judging one against the other
        // compares a value to a threshold calibrated for a different quantity, and near-zero — the
        // healthiest possible probe result — sits far outside the band by construction.
        probeRowExists();
        queryLaneReturns(0.2434);
        documentLaneReturns(0.0);   // perfectly healthy, and nowhere near [0.2425, 0.2440]

        RetrievalCanaryCheck.CanaryReading reading = check(storeProbeOn()).run();

        assertThat(reading.storeProbe()).hasValue(0.0);
        assertThat(reading.skipped()).isFalse();
    }

    @Test
    void theStoreProbeStillRunsOnTheBootWhereTheCanaryTrips() {
        // The ordering that makes the flag usable at all. It gets switched on BECAUSE the canary is
        // tripping; if the probe ran after the throw it would never produce a line on the one boot
        // anybody wanted it for.
        probeRowExists();
        queryLaneReturns(0.71);
        documentLaneReturns(0.000001);

        assertThatThrownBy(() -> check(storeProbeOn()).run())
                .isInstanceOf(IllegalStateException.class);

        verify(voyage).embedDocuments(List.of(canaryText()));
    }

    // ---------------------------------------------------------------- the absent probe

    @Test
    void anAbsentProbeRowSkipsRatherThanFailing() {
        // THE BOOTSTRAPPING EXEMPTION, and it is mechanical rather than lenient. The probe row is
        // written by IngestionPipeline, an ApplicationRunner that fires AFTER context refresh, while
        // this check is a SmartInitializingSingleton that runs DURING it. On the boot that would
        // populate the probe, this code necessarily runs first and necessarily finds nothing — so
        // failing here would kill that boot before the runner could execute and leave the table
        // permanently empty. It is also the exact state V4 leaves behind, since the migration drops
        // the old canary chunk and invalidates its fingerprint.
        when(probe.findProbe()).thenReturn(Optional.empty());

        RetrievalCanaryCheck.CanaryReading reading = check(storeProbeOff()).run();

        assertThat(reading.skipped()).isTrue();
        assertThat(reading.distance()).isEmpty();
        assertThat(reading.storeProbe()).isEmpty();
        verify(voyage, never()).embedQuery(anyString());   // nothing to probe, so nothing is billed
    }

    // ---------------------------------------------------------------- fixtures

    private RetrievalCanaryCheck check(CanaryProperties props) {
        return new RetrievalCanaryCheck(probe, voyage, props, voyageProperties());
    }

    /**
     * A stored probe row. MOCKED rather than constructed, deliberately: {@link CanaryProbe} has no
     * public constructor because nothing in production builds one — both writes go through a native
     * upsert — and adding one purely so a test could call it would be production API that exists only
     * for tests. The check reads exactly one field off this row, so a mock is the whole object.
     */
    private void probeRowExists() {
        CanaryProbe row = mock(CanaryProbe.class);
        lenient().when(row.getEmbeddingModelId()).thenReturn(STORED_MODEL);
        when(probe.findProbe()).thenReturn(Optional.of(row));
    }

    private void queryLaneReturns(double distance) {
        when(voyage.embedQuery(canaryText())).thenReturn(QUERY_VECTOR);
        when(probe.distanceFrom(eq(VectorLiterals.toLiteral(QUERY_VECTOR))))
                .thenReturn(Optional.of(distance));
    }

    private void documentLaneReturns(double distance) {
        when(voyage.embedDocuments(List.of(canaryText()))).thenReturn(List.of(DOCUMENT_VECTOR));
        when(probe.distanceFrom(eq(VectorLiterals.toLiteral(DOCUMENT_VECTOR))))
                .thenReturn(Optional.of(distance));
    }

    /**
     * The exact string both lanes must embed — the frozen constant, not a hand-rolled variant.
     *
     * <p>V3's version of this helper rebuilt {@code BREADCRUMB + "\n" + CONTENT} from test constants,
     * which was a second definition of the concatenation and could drift from
     * {@code Chunk.embeddingInput}. Reading the real constant removes that possibility: if the canary
     * text or the separator changes, this test embeds the changed value automatically and
     * CanaryDocumentTest is the thing that fails, which is where a text change SHOULD fail.
     */
    private static String canaryText() {
        return CanaryDocument.fingerprintContent();
    }

    private static CanaryProperties storeProbeOff() {
        return canaryProperties(false);
    }

    private static CanaryProperties storeProbeOn() {
        return canaryProperties(true);
    }

    private static CanaryProperties canaryProperties(boolean storeProbe) {
        return new CanaryProperties(true,
                new CanaryProperties.Band(BAND_MIN, BAND_MAX),
                new CanaryProperties.StoreProbe(storeProbe));
    }

    private static VoyageProperties voyageProperties() {
        return new VoyageProperties("test-key", "http://localhost", "voyage-4-large", "voyage-4-lite",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 2000, 300);
    }
}
