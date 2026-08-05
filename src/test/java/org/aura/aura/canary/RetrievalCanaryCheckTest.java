package org.aura.aura.canary;

import org.aura.aura.client.EmbeddingInputType;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.config.CanaryProperties;
import org.aura.aura.config.VoyageProperties;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.KbChunk;
import org.aura.aura.util.VectorLiterals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The canary's LOGIC, with fixed vectors and stubbed distances at the two mocked boundaries.
 *
 * <p>This is where the tradeoff written into application-test.yml gets paid for. The canary is
 * disabled in every test profile, so nothing in CI ever makes the live Voyage call it exists to make
 * — which means the band comparison, the refuse-to-boot path, the skip rules and the probe's on/off
 * behaviour all have to be provable without one. They are, because none of them is about the network:
 * they are about what this class does with a number.
 *
 * <p>The distances are stubbed at the REPOSITORY, which is the honest seam. The arithmetic that
 * produces them is Postgres's {@code <=>}, and pinning Postgres's arithmetic is PgVectorSchemaIT's
 * job — asserting it a second time here with a JVM reimplementation would be testing a copy of the
 * thing rather than the thing.
 */
@ExtendWith(MockitoExtension.class)
class RetrievalCanaryCheckTest {

    private static final String DOC = "refund-policy.md";
    private static final int INDEX = 0;
    private static final String BREADCRUMB = "Refund Policy";
    private static final String CONTENT = "Customers have 30 days from the delivery date.";

    // The MEASURED band from application.yml. Using the real numbers rather than round ones keeps the
    // test honest about what it is guarding: these are the values a boot actually compares against.
    private static final double BAND_MIN = 0.242516;
    private static final double BAND_MAX = 0.244003;

    // Fixed, tiny, and DIFFERENT from each other, so the two lanes produce different pgvector literals
    // and each can be stubbed independently — which is what lets a test prove the store probe made its
    // own separate call rather than reusing the query lane's.
    private static final float[] QUERY_VECTOR = {0.1f, 0.2f, 0.3f};
    private static final float[] DOCUMENT_VECTOR = {0.4f, 0.5f, 0.6f};

    @Mock ChunkRepository chunks;
    @Mock VoyageEmbeddingClient voyage;

    /**
     * The client reports its own lanes, so a mocked client has to be told what to report. Stubbed
     * {@code lenient()} because the two absent-row tests below never reach a message or a log line
     * and would otherwise trip strict-stub checking.
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
        canaryRowExists();
        queryLaneReturns(0.2434);   // mid-band, near the measured mean

        RetrievalCanaryCheck.CanaryReading reading = check(storeProbeOff()).run();

        assertThat(reading.skipped()).isFalse();
        assertThat(reading.distance()).hasValue(0.2434);
        assertThat(reading.storeProbe()).isEmpty();
    }

    @Test
    void aDistanceAboveTheBandRefusesToBootAndNamesBothSidesOfThePairing() {
        canaryRowExists();
        queryLaneReturns(0.71);   // the shape of a cross-family mismatch: not an error, just far

        assertThatThrownBy(() -> check(storeProbeOff()).run())
                .isInstanceOf(IllegalStateException.class)
                // The observed value AND the band. "Out of band" alone sends the reader looking;
                // "0.71 against [0.2425, 0.2440]" tells them how badly and in which direction.
                .hasMessageContaining("0.71")
                .hasMessageContaining("0.242516")
                .hasMessageContaining("0.244003")
                // BOTH SIDES of the pairing, because the failure is a relationship between two things
                // and naming only one of them makes the message a riddle. Which model, which lane,
                // which stored row, on each side.
                .hasMessageContaining("voyage-4-large").hasMessageContaining("document")
                .hasMessageContaining("voyage-4-lite").hasMessageContaining("query")
                .hasMessageContaining(DOC + "#" + INDEX);
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
        canaryRowExists();
        when(voyage.queryInputType()).thenReturn(EmbeddingInputType.DOCUMENT);   // the flip
        when(voyage.embedQuery(embeddingInput())).thenReturn(QUERY_VECTOR);
        when(chunks.distanceFrom(eq(DOC), eq(INDEX), eq(VectorLiterals.toLiteral(QUERY_VECTOR))))
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
        canaryRowExists();
        queryLaneReturns(0.0668);

        assertThatThrownBy(() -> check(storeProbeOff()).run())
                .hasMessageContaining("input_type was changed");
    }

    @Test
    void aDistanceBelowTheBandAlsoRefusesToBoot() {
        // The direction that looks like good news and is not. A distance far BELOW the measured floor
        // means the two lanes suddenly agree more than they ever have — which does not happen because
        // retrieval improved. It happens because both sides became the same model, or the stored
        // vector was overwritten by a query-lane one. A one-sided check would wave that through.
        canaryRowExists();
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
        canaryRowExists();
        queryLaneReturns(BAND_MIN);
        assertThat(check(storeProbeOff()).run().distance()).hasValue(BAND_MIN);

        queryLaneReturns(BAND_MAX);
        assertThat(check(storeProbeOff()).run().distance()).hasValue(BAND_MAX);
    }

    // ---------------------------------------------------------------- the store-side probe

    @Test
    void theStoreProbeIsCompletelyInertWhenTheFlagIsOff() {
        canaryRowExists();
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
        canaryRowExists();
        queryLaneReturns(0.2434);
        documentLaneReturns(0.000002);   // same model both sides: near zero, as expected

        RetrievalCanaryCheck.CanaryReading reading = check(storeProbeOn()).run();

        verify(voyage).embedDocuments(List.of(embeddingInput()));
        assertThat(reading.storeProbe()).hasValue(0.000002);
    }

    @Test
    void theStoreProbeNeverFailsTheBootEvenWhenItsNumberIsOutsideTheQueryLaneBand() {
        // The probe REPORTS, it does not gate — and this is the test that keeps someone from
        // "tidying" it into a second guard. Its number is a document-vs-document measurement; the
        // band was measured for large/document against lite/query. Judging one against the other
        // compares a value to a threshold calibrated for a different quantity, and near-zero — the
        // healthiest possible probe result — sits far outside the band by construction.
        canaryRowExists();
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
        canaryRowExists();
        queryLaneReturns(0.71);
        documentLaneReturns(0.000001);

        assertThatThrownBy(() -> check(storeProbeOn()).run())
                .isInstanceOf(IllegalStateException.class);

        verify(voyage).embedDocuments(List.of(embeddingInput()));
    }

    // ---------------------------------------------------------------- the two absent-row cases

    @Test
    void anEmptyCorpusSkipsRatherThanFailing() {
        // The chicken-and-egg exemption. The corpus is ingested BY a boot (KbCorpusLoader), so
        // failing a boot because the corpus is empty would make the ingesting boot impossible to run
        // and leave the table permanently empty.
        when(chunks.findBySourceDocAndChunkIndex(DOC, INDEX)).thenReturn(Optional.empty());
        when(chunks.count()).thenReturn(0L);

        RetrievalCanaryCheck.CanaryReading reading = check(storeProbeOff()).run();

        assertThat(reading.skipped()).isTrue();
        assertThat(reading.distance()).isEmpty();
        verify(voyage, never()).embedQuery(anyString());   // nothing to probe, so nothing is billed
    }

    @Test
    void aPopulatedCorpusMissingTheCanaryRowFails() {
        // The other side of the same condition, and the one that is genuinely drift: chunks exist,
        // but not the one the canary names. That means the corpus was ingested under different chunk
        // boundaries than aura.canary.* was configured against — and the band belongs to the TEXT, so
        // re-pointing the identity without re-measuring would silently compare against a band that
        // was never measured for the new chunk.
        when(chunks.findBySourceDocAndChunkIndex(DOC, INDEX)).thenReturn(Optional.empty());
        when(chunks.count()).thenReturn(33L);

        assertThatThrownBy(() -> check(storeProbeOff()).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot find its chunk")
                .hasMessageContaining("aura.canary.source-doc=" + DOC)
                .hasMessageContaining("re-measure the band");
    }

    // ---------------------------------------------------------------- fixtures

    private RetrievalCanaryCheck check(CanaryProperties props) {
        return new RetrievalCanaryCheck(chunks, voyage, props, voyageProperties());
    }

    private void canaryRowExists() {
        when(chunks.findBySourceDocAndChunkIndex(DOC, INDEX)).thenReturn(Optional.of(storedChunk()));
    }

    private void queryLaneReturns(double distance) {
        when(voyage.embedQuery(embeddingInput())).thenReturn(QUERY_VECTOR);
        when(chunks.distanceFrom(eq(DOC), eq(INDEX), eq(VectorLiterals.toLiteral(QUERY_VECTOR))))
                .thenReturn(Optional.of(distance));
    }

    private void documentLaneReturns(double distance) {
        when(voyage.embedDocuments(List.of(embeddingInput()))).thenReturn(List.of(DOCUMENT_VECTOR));
        when(chunks.distanceFrom(eq(DOC), eq(INDEX), eq(VectorLiterals.toLiteral(DOCUMENT_VECTOR))))
                .thenReturn(Optional.of(distance));
    }

    /**
     * The exact string both lanes must embed — built the same way ingestion built it. Asserting
     * against this rather than against {@code anyString()} is what pins the canary to the stored
     * text: embedding a hand-rolled variant would shift the measured distance for a reason unrelated
     * to the lanes, and the band would then reject a healthy system.
     */
    private static String embeddingInput() {
        return BREADCRUMB + "\n" + CONTENT;
    }

    private static KbChunk storedChunk() {
        return new KbChunk(UUID.randomUUID(), DOC, INDEX, BREADCRUMB, CONTENT, 12,
                new float[]{0.9f, 0.8f, 0.7f}, "voyage-4-large");
    }

    private static CanaryProperties storeProbeOff() {
        return canaryProperties(false);
    }

    private static CanaryProperties storeProbeOn() {
        return canaryProperties(true);
    }

    private static CanaryProperties canaryProperties(boolean storeProbe) {
        return new CanaryProperties(true, DOC, INDEX,
                new CanaryProperties.Band(BAND_MIN, BAND_MAX),
                new CanaryProperties.StoreProbe(storeProbe));
    }

    private static VoyageProperties voyageProperties() {
        return new VoyageProperties("test-key", "http://localhost", "voyage-4-large", "voyage-4-lite",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 2000, 300);
    }
}
