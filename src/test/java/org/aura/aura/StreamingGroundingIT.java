package org.aura.aura;

import com.redis.testcontainers.RedisContainer;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.DocumentRepository;
import org.aura.aura.store.KbFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.aura.aura.AnthropicMessages.classifierOk;
import static org.aura.aura.AnthropicMessages.resolverStream;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Day 16, Decision 4: the SSE endpoint BUFFERS until the grounding gates pass.
 *
 * <h2>Why this class exists at all</h2>
 * Before today there was no test anywhere that drove {@code /resolve/stream}. That is not a
 * coincidence — it is why the endpoint shipped ungated: Day 16 put every new enforcement on the
 * response, `resolve()` was the only door that ran it, and nothing observed the second door from
 * outside. A suite that never exercises a transport cannot notice when a rule stops applying to it.
 *
 * <p>The fixture splits the envelope across several {@code content_block_delta} frames on purpose.
 * Many fragments in and exactly ONE delta out is the assertion that distinguishes buffering from
 * forwarding; a single-frame fixture would pass against the old pump too.
 *
 * <p>Real SDK, real controller, real retrieval, real Postgres — only Anthropic (MockWebServer) and
 * Voyage (mocked at the Java boundary) are stood in for, exactly as in {@link AnthropicTransportIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "it"})
@Testcontainers
class StreamingGroundingIT extends PostgresBackedContext {

    @Container
    @ServiceConnection
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    static final MockWebServer ANTHROPIC = new MockWebServer();
    static {
        try {
            ANTHROPIC.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void seam(DynamicPropertyRegistry registry) {
        registry.add("aura.anthropic.base-url", () -> ANTHROPIC.url("/").toString());
    }

    @AfterAll
    static void stopServer() throws IOException {
        ANTHROPIC.shutdown();
    }

    @Value("${local.server.port}") int port;
    @MockitoBean VoyageEmbeddingClient voyage;
    @Autowired ChunkRepository chunks;
    @Autowired DocumentRepository documents;

    RestClient rest;

    @BeforeEach
    void setUp() {
        KbFixtures.seedOneGroundingChunk(chunks, documents);
        when(voyage.embedQuery(anyString())).thenReturn(queryVector());
        ANTHROPIC.setDispatcher(new QueueDispatcher());
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @AfterAll
    static void clearSharedCorpus(@Autowired ChunkRepository chunks,
                                  @Autowired DocumentRepository documents) {
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
    }

    /** A grounded answer citing the seeded chunk: delivered whole, after the gates, with an outcome. */
    @Test
    void groundedAnswerIsDeliveredAsOneDeltaAfterTheGatesPass() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverStream("{\"reply\":\"You have 30 days from delivery.\",\"citations\":[\""
                + KbFixtures.GROUNDING_CHUNK_ID + "\"],\"escalate\":false,\"grounded\":true}", 6));

        String body = stream("sse1", "How long do I have to request a refund?");

        // ONE delta, carrying the whole reply. Six fragments went in.
        assertThat(countFrames(body, "delta")).isEqualTo(1);
        assertThat(body).contains("You have 30 days from delivery.");
        assertThat(body).contains("\"outcome\":\"RESOLVED\"");

        // NOT A SINGLE BYTE OF ENVELOPE SCAFFOLDING reached the customer. This is the property the
        // extractor used to provide character-by-character and that buffering now provides by
        // construction — asserted rather than assumed, because it is the one that would paint
        // {"reply":"You have 30 d across a screen if it broke.
        assertThat(payloads(body)).noneMatch(line -> line.contains("\\\"grounded\\\"")
                || line.contains("{\\\"reply\\\""));
    }

    /**
     * THE REGRESSION THIS DECISION EXISTS TO CLOSE.
     *
     * <p>Clause (c) tells the model to leave {@code reply} EMPTY when the excerpts cannot answer the
     * ticket. Until Decision 4, the streaming path had no G3 to substitute anything, so a
     * well-behaved model produced a stream containing no text at all: the customer watched a
     * classification frame, nothing, and a done frame. Before Day 16 that same ticket produced prose.
     *
     * <p>The assertion is therefore that an EMPTY model reply becomes a NON-EMPTY escalation — the
     * gate supplying what the contract told the model not to write.
     */
    @Test
    void anUngroundedEmptyReplyBecomesAnEscalationRatherThanAnEmptyStream() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverStream(
                "{\"reply\":\"\",\"citations\":[],\"escalate\":true,\"grounded\":false}", 4));

        String body = stream("sse2", "Do you offer a student discount?");

        assertThat(countFrames(body, "delta")).isEqualTo(1);
        assertThat(body).contains("escalated");
        assertThat(body).contains("\"outcome\":\"ESCALATED_TO_HUMAN\"");
        // A business outcome at HTTP 200 (ADR-013), NOT an error frame — the ERROR frame stays
        // reserved for a transport failure, where there is no answer at all.
        assertThat(countFrames(body, "error")).isZero();
        assertThat(countFrames(body, "done")).isEqualTo(1);
    }

    /** A citation the request never supplied: G4 fires on this path too, not just the blocking one. */
    @Test
    void aForeignCitationIsCaughtOnTheStreamingPathAsWell() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverStream("{\"reply\":\"Refunds take 3 business days.\","
                + "\"citations\":[\"00000000-0000-0000-0000-0000000000ff\"],"
                + "\"escalate\":false,\"grounded\":true}", 5));

        String body = stream("sse3", "How long does a refund take?");

        assertThat(body).contains("\"outcome\":\"ESCALATED_TO_HUMAN\"");
        // The fabricated answer never reached the customer — the whole point of buffering.
        assertThat(body).doesNotContain("Refunds take 3 business days.");
    }

    /**
     * An unreadable envelope. On the blocking path G0 retries first; here it cannot — a stream cannot
     * be resumed and re-opening one would re-bill the whole generation — so it escalates immediately.
     * What matters is that it escalates rather than shipping text nobody could verify.
     */
    @Test
    void anUnreadableEnvelopeEscalatesInsteadOfShippingUnverifiedText() {
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverStream("{\"reply\":\"cut off mid-", 3));

        String body = stream("sse4", "How long do I have to request a refund?");

        assertThat(body).contains("\"outcome\":\"ESCALATED_TO_HUMAN\"");
        assertThat(body).doesNotContain("cut off mid-");
    }

    // ---------------------------------------------------------------- helpers

    /** Drives the real endpoint. The body arrives complete because the pump closes the emitter. */
    private String stream(String ticketId, String message) {
        ResponseEntity<String> resp = rest.post()
                .uri("/api/v1/tickets/{id}/resolve/stream", ticketId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message))
                .retrieve()
                .toEntity(String.class);
        assertThat(resp.getStatusCode().value())
                .as("an escalation is still a 200; only a transport fault is not")
                .isEqualTo(200);
        return resp.getBody() == null ? "" : resp.getBody();
    }

    private static int countFrames(String body, String eventName) {
        return (int) body.lines().filter(line -> line.equals("event:" + eventName)
                || line.equals("event: " + eventName)).count();
    }

    private static java.util.List<String> payloads(String body) {
        return body.lines().filter(line -> line.startsWith("data:")).toList();
    }
}
