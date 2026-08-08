package org.aura.aura;

import com.redis.testcontainers.RedisContainer;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.aura.aura.client.VoyageEmbeddingClient;
import org.aura.aura.store.ChunkRepository;
import org.aura.aura.store.DocumentRepository;
import org.aura.aura.store.KbFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;

import static org.aura.aura.AnthropicMessages.classifierOk;
import static org.aura.aura.AnthropicMessages.resolve;
import static org.aura.aura.AnthropicMessages.resolverOk;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Day 11 (TASK 5): the Day 9 cache is an availability layer wearing a cost costume — when Redis dies,
 * the customer must not be able to tell. This class uses its OWN Redis container because it STOPS Redis
 * mid-test; it must never share a container with {@link AnthropicTransportIT}.
 */
// "test" excludes ConversationRunner + supplies the API key; "it" adds the aggressive MockWebServer-only
// timeout/retry-wait overrides (listed last, so it wins). Evals use "test" alone and keep prod timeouts.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "it"})
@Testcontainers
// Day 14: full application context, so it needs a Postgres (see PostgresBackedContext).
class RedisDegradationIT extends PostgresBackedContext {

    // OWN container — stopped mid-test below. @ServiceConnection auto-wires spring.data.redis.* to it.
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
    RestClient rest;

    // The SAME message both times, so the cache KEY is identical — the second call WOULD be a hit if
    // Redis were alive. That is what makes the request-count growth a proof of cache bypass.
    private static final String TICKET = "cache-death";
    private static final String MESSAGE = "How long does standard shipping take to Oregon?";

    // Day 14: same reason as AnthropicTransportIT — /resolve now embeds the ticket first, and the only
    // fake server here is the Anthropic one. See that class for the full note.
    @MockitoBean VoyageEmbeddingClient voyage;

    @Autowired ChunkRepository chunks;
    @Autowired DocumentRepository documents;

    /** Day 16: one citable excerpt, or the scripted success below escalates at G4 (see KbFixtures). */
    @BeforeEach
    void seedOneExcerpt() {
        KbFixtures.seedOneGroundingChunk(chunks, documents);
    }

    /** The Postgres container is shared even though this class owns its Redis; leave the corpus clean. */
    @AfterAll
    static void clearSharedCorpus(@Autowired ChunkRepository chunks,
                                  @Autowired DocumentRepository documents) {
        chunks.deleteAllInBatch();
        documents.deleteAllInBatch();
    }

    @BeforeEach
    void setup() {
        // Full width — see the note in AnthropicTransportIT: the Postgres is shared, so a short vector
        // is a dimension error waiting on whichever class seeded rows first.
        when(voyage.embedQuery(anyString())).thenReturn(queryVector());
        rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    @Test
    void cacheDeathIsInvisibleToTheCustomer() {
        ANTHROPIC.setDispatcher(new QueueDispatcher());

        // (1) First time: cache MISS → real pipeline → answer, then cached. [clf, res] = 2 requests.
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverOk(KbFixtures.GROUNDING_CHUNK_ID.toString()));
        int start = ANTHROPIC.getRequestCount();
        ResponseEntity<String> first = resolve(rest, TICKET, MESSAGE);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(ANTHROPIC.getRequestCount() - start).isEqualTo(2);

        // (2) Redis dies.
        REDIS.stop();

        // (3) The same request A again. With Redis alive this would be a cache HIT (0 model calls); with
        // Redis down the cache fails OPEN to a MISS, so the pipeline recomputes. Enqueue identical
        // canned responses so the recomputed answer is byte-identical to the cached one.
        ANTHROPIC.enqueue(classifierOk());
        ANTHROPIC.enqueue(resolverOk(KbFixtures.GROUNDING_CHUNK_ID.toString()));
        ResponseEntity<String> second = resolve(rest, TICKET, MESSAGE);
        assertThat(second.getStatusCode().value()).isEqualTo(200);

        // The customer cannot tell the cache died: a byte-identical answer...
        assertThat(second.getBody()).isEqualTo(first.getBody());
        // ...but it was RECOMPUTED, not served from cache — the request count grew 2 → 4, proving the
        // cache was BYPASSED, not hit. That indistinguishability is the promise.
        assertThat(ANTHROPIC.getRequestCount() - start).isEqualTo(4);
    }
}
