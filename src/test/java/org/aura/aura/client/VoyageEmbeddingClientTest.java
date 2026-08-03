package org.aura.aura.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.aura.aura.config.VoyageProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transport tests for the Voyage client against a REAL local HTTP server (MockWebServer), reusing the
 * Day 11 test kit's approach: no Mockito at the client boundary, so JSON serialization, the auth
 * header, the timeouts, and the status→exception mapping are all genuinely exercised.
 *
 * <p>The Spring context is SLICED (the Day 8 {@code ResilienceTestConfig} idiom) rather than the full
 * application: it needs exactly the client, its properties, and the Resilience4j auto-configuration
 * that reads {@code resilience4j.retry.instances.voyage} from the production application.yml. That
 * matters — the retry policy under test here is the one production runs, not a copy.
 */
@SpringBootTest(
        classes = VoyageEmbeddingClientTest.VoyageTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class VoyageEmbeddingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Started in a STATIC INITIALIZER so it is already listening before the @Bean below reads its port
    // during context load — a @BeforeAll would race that.
    static final MockWebServer VOYAGE = new MockWebServer();
    static {
        try {
            VOYAGE.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopServer() throws IOException {
        VOYAGE.shutdown();
    }

    @DynamicPropertySource
    static void fastRetryWaits(DynamicPropertyRegistry registry) {
        // Same law as application-it.yml: shorten only the WAIT, never the behaviour. max-attempts (3)
        // and retry-exceptions are INHERITED from application.yml, so the thing being asserted — how
        // many calls happen and which failures earn them — is production's, not the test's.
        registry.add("resilience4j.retry.instances.voyage.wait-duration", () -> "20ms");
        registry.add("resilience4j.retry.instances.voyage.enable-exponential-backoff", () -> false);
        registry.add("resilience4j.retry.instances.voyage.enable-randomized-wait", () -> false);
    }

    @Configuration(proxyBeanMethods = false)
    // Day 13: this slice does not use a database, and it activates no profile, so it cannot inherit
    // application-test.yml's exclusion — see ClassificationResilienceTest for the same note.
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import(VoyageEmbeddingClient.class)
    static class VoyageTestConfig {
        @Bean
        VoyageProperties voyageProperties() {
            // The config seam: the REAL client bean reads the same properties production reads, and only
            // the base-url value differs. Timeouts are tightened to 500ms so the hang test trips in
            // milliseconds and asserts an EXCEPTION TYPE rather than an elapsed duration.
            return new VoyageProperties(
                    "test-key",
                    "http://localhost:" + VOYAGE.getPort(),
                    "voyage-4-large",
                    "voyage-4-lite",
                    Duration.ofMillis(500),
                    Duration.ofMillis(500),
                    2000, 300);
        }
    }

    @Autowired
    VoyageEmbeddingClient client;   // the AOP-proxied bean — @Retry is live here

    @BeforeEach
    void isolate() throws InterruptedException {
        // Fresh response queue per test, so a scenario that fails mid-way cannot leak enqueued
        // responses into the next one...
        VOYAGE.setDispatcher(new QueueDispatcher());
        // ...and drain any recorded requests, so takeRequest() below always returns THIS test's first
        // request rather than a leftover.
        while (VOYAGE.takeRequest(1, TimeUnit.MILLISECONDS) != null) { /* drain */ }
    }

    // ---------------------------------------------------------------- THE asymmetric assertion

    @Test
    void embedDocumentsSendsTheDocumentModelAndTheDocumentInputType() throws Exception {
        VOYAGE.enqueue(embeddingsOk(new float[]{0.1f, 0.2f, 0.3f}, new float[]{0.4f, 0.5f, 0.6f}));

        List<float[]> vectors = client.embedDocuments(List.of("chunk one", "chunk two"));

        RecordedRequest request = VOYAGE.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/embeddings");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");

        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        // The premium model on the one-time offline lane. This assertion IS the design: if a refactor
        // ever collapsed the two public methods into one, this is the test that notices, because
        // nothing else in the system would fail — wrong-model vectors are perfectly valid vectors.
        assertThat(body.path("model").asText()).isEqualTo("voyage-4-large");
        assertThat(body.path("input_type").asText()).isEqualTo("document");
        assertThat(body.path("input")).hasSize(2);
        assertThat(body.path("input").get(0).asText()).isEqualTo("chunk one");

        // Response parsed into primitive float[], in request order.
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);
    }

    @Test
    void embedQuerySendsTheQueryModelAndTheQueryInputType() throws Exception {
        VOYAGE.enqueue(embeddingsOk(new float[]{0.7f, 0.8f}));

        float[] vector = client.embedQuery("How long does delivery to Canada take?");

        JsonNode body = MAPPER.readTree(VOYAGE.takeRequest().getBody().readUtf8());
        // The economy model on the per-ticket online lane — the other half of the asymmetry. Note the
        // input is still a LIST of one: the API is a batch API, and the single-query convenience is
        // ours, not the provider's.
        assertThat(body.path("model").asText()).isEqualTo("voyage-4-lite");
        assertThat(body.path("input_type").asText()).isEqualTo("query");
        assertThat(body.path("input")).hasSize(1);

        assertThat(vector).containsExactly(0.7f, 0.8f);
    }

    @Test
    void outOfOrderResponseIsReorderedByIndex() throws Exception {
        // The API does not promise response order, so the client sorts by `index`. A mis-ordered batch
        // would attach every vector to the wrong chunk — a corruption with no exception attached.
        VOYAGE.enqueue(json("""
                {"object":"list","model":"voyage-4-large","usage":{"total_tokens":9},"data":[
                  {"object":"embedding","index":1,"embedding":[9.0]},
                  {"object":"embedding","index":0,"embedding":[1.0]}
                ]}"""));

        List<float[]> vectors = client.embedDocuments(List.of("first", "second"));

        assertThat(vectors.get(0)).containsExactly(1.0f);
        assertThat(vectors.get(1)).containsExactly(9.0f);
    }

    // ---------------------------------------------------------------- retry policy

    @Test
    void rateLimitIsTransient_retriedOnceThenSucceeds() {
        VOYAGE.enqueue(error(429, "rate limited"));
        VOYAGE.enqueue(embeddingsOk(new float[]{0.5f}));
        int before = VOYAGE.getRequestCount();

        float[] vector = client.embedQuery("Can I get my money back?");

        assertThat(vector).containsExactly(0.5f);
        // The retry is proven by a COUNT of requests received, never by an elapsed duration.
        assertThat(VOYAGE.getRequestCount() - before).isEqualTo(2);
    }

    @Test
    void unauthorizedIsPermanent_failsFastWithNoRetry() {
        VOYAGE.enqueue(error(401, "invalid api key"));
        int before = VOYAGE.getRequestCount();

        assertThatThrownBy(() -> client.embedQuery("anything"))
                .isInstanceOf(VoyagePermanentException.class)
                .hasMessageContaining("401");

        // ONE request. A 401 is not on the transient allowlist, so Resilience4j never retries it —
        // retrying a bad key just turns one rejection into three and delays the real error by the
        // full backoff.
        assertThat(VOYAGE.getRequestCount() - before).isEqualTo(1);
    }

    @Test
    void serverErrorIsTransient_retriedUntilTheBudgetIsSpent() {
        VOYAGE.enqueue(error(503, "unavailable"));
        VOYAGE.enqueue(error(503, "unavailable"));
        VOYAGE.enqueue(error(503, "unavailable"));
        int before = VOYAGE.getRequestCount();

        assertThatThrownBy(() -> client.embedQuery("anything"))
                .isInstanceOf(VoyageTransientException.class);

        // 3 = max-attempts (1 original + 2 retries), read from the production application.yml.
        assertThat(VOYAGE.getRequestCount() - before).isEqualTo(3);
    }

    @Test
    void readTimeoutTakesTheTransientPath() {
        // A response the client never finishes reading: the body is delayed well past the 500ms read
        // timeout. There is no HTTP status to map — the failure arrives as a socket timeout — and it
        // must still land on the transient side, because "the provider did not answer" is exactly the
        // shape a 5xx has from the caller's point of view.
        Duration overTimeout = Duration.ofSeconds(2);
        VOYAGE.enqueue(delayed(embeddingsOk(new float[]{0.5f}), overTimeout));
        VOYAGE.enqueue(delayed(embeddingsOk(new float[]{0.5f}), overTimeout));
        VOYAGE.enqueue(delayed(embeddingsOk(new float[]{0.5f}), overTimeout));
        int before = VOYAGE.getRequestCount();

        assertThatThrownBy(() -> client.embedQuery("anything"))
                .isInstanceOf(VoyageTransientException.class)
                .hasMessageContaining("timeout");

        // Transient ⇒ retried, so the delay in the SCRIPT produces a request COUNT in the assertion.
        // If someone deletes the explicit read timeout, this test hangs on the first 2s delay and then
        // fails loudly rather than passing slowly.
        assertThat(VOYAGE.getRequestCount() - before).isEqualTo(3);
    }

    // ---------------------------------------------------------------- fixtures

    private static MockResponse embeddingsOk(float[]... vectors) {
        String data = Stream.iterate(0, i -> i + 1).limit(vectors.length)
                .map(i -> "{\"object\":\"embedding\",\"index\":" + i + ",\"embedding\":["
                        + vectorLiteral(vectors[i]) + "]}")
                .collect(Collectors.joining(","));
        return json("{\"object\":\"list\",\"model\":\"voyage-4-large\","
                + "\"usage\":{\"total_tokens\":42},\"data\":[" + data + "]}");
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static MockResponse error(int status, String detail) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"detail\":\"" + detail + "\"}");
    }

    private static MockResponse delayed(MockResponse response, Duration delay) {
        return response.setBodyDelay(delay.toMillis(), TimeUnit.MILLISECONDS);
    }
}
