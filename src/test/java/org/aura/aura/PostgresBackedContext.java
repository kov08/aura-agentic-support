package org.aura.aura;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A Postgres for any test that loads the FULL application context.
 *
 * <h2>Why this exists as of Day 14</h2>
 * Day 13 made the database opt-IN under test: {@code application-test.yml} excludes
 * {@code DataSourceAutoConfiguration}, so the DB-less majority of the suite needed no Docker, and the
 * two classes that wanted Postgres opted back in. That was the right trade while the database was
 * used only by an offline, flag-gated corpus loader.
 *
 * <p>Day 14 changed the fact underneath it. {@code RetrievalService} is on the live request path and
 * injects {@code ChunkRepository}, so a full application context without a {@code DataSource} is no
 * longer a configuration that can exist — it is not a lighter version of production, it is one that
 * cannot serve a single ticket. A test asserting that such a context starts would be asserting
 * something about a system nobody runs.
 *
 * <p>So the rule is narrowed rather than reversed: contexts that load the WHOLE application get a
 * database, and SLICED contexts ({@code ClassificationResilienceTest},
 * {@code VoyageEmbeddingClientTest}, {@code ResolverResilienceTest}) keep their own explicit
 * {@code @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)} and stay offline.
 * Since every full-context class is an {@code *IT} or a tagged runner, {@code mvn test} remains
 * fast, free and Docker-free — which was the property Day 13's exclusion was protecting all along.
 *
 * <h2>One container for all of them</h2>
 * Started once in a static initializer and never stopped — the "singleton container" pattern — rather
 * than a per-class {@code @Container}. Four classes each paying a container start is four times the
 * wait for a database whose STATE none of them share (Flyway is idempotent, and every one of these
 * classes either ignores {@code kb_chunks} or populates its own rows). Testcontainers' Ryuk sidecar
 * removes it when the JVM exits, so nothing leaks.
 *
 * <p>{@code @DynamicPropertySource} rather than {@code @ServiceConnection} because it also has to
 * undo the profile's exclusion, and it is the highest-precedence property source — so one method here
 * replaces both the container wiring and a {@code properties = "spring.autoconfigure.exclude="} line
 * on every subclass.
 */
public abstract class PostgresBackedContext {

    // pgvector's image, declared as a substitute for the plain `postgres` one: PostgreSQLContainer
    // refuses image names it does not recognise as Postgres, and this is a different repository.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    /**
     * A full-width query vector for classes that mock {@link org.aura.aura.client.VoyageEmbeddingClient}
     * but do not care what retrieval returns.
     *
     * <p>It has to be the real width. Sharing one container means sharing {@code kb_chunks}, so a class
     * that runs after one which seeded real rows will compare against {@code vector(1024)} — and
     * pgvector rejects a dimension mismatch outright, which arrives as an unexplained 500 in a test
     * that has nothing to do with vectors. Learned by shipping the short version first: it passed, and
     * then failed for whichever class happened to run second.
     */
    protected static float[] queryVector() {
        float[] vector = new float[org.aura.aura.store.KbChunk.EMBEDDING_DIMENSION];
        vector[0] = 1.0f;
        return vector;
    }

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        // Opt back IN to the database. An empty value at the highest-precedence property source
        // REPLACES application-test.yml's exclusion list rather than merging with it.
        registry.add("spring.autoconfigure.exclude", () -> "");
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
