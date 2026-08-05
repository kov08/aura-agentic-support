package org.aura.aura;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole-application wiring smoke test: does the real context start?
 *
 * <h2>Why this is an IT as of Day 14 (it was AuraApplicationTests)</h2>
 * The assertion has not changed; what it takes to make the assertion has. Retrieval is on the request
 * path now, so the application context genuinely requires a {@code DataSource} — and the only honest
 * options were to give this test a Postgres, or to keep excluding the database and assert that a
 * context nobody can actually run starts successfully.
 *
 * <p>A Postgres means Docker, and Docker in Surefire would make {@code mvn test} — the fast, free,
 * offline suite — depend on a running daemon. Day 13 weighed that exact trade and came down against
 * it. Renaming to {@code *IT} keeps both properties: {@code mvn test} stays offline, and
 * {@code mvn verify} still proves the real application wires up.
 *
 * <p>The cost, stated rather than glossed: a broken bean graph is now caught one phase later than it
 * used to be. That is the same price every other {@code *IT} here already pays.
 *
 * <p>"test" profile: excludes ConversationRunner (@Profile("!test")), a dev-time demo that fires a
 * live Claude call on startup, and disables the Day 14 boot canary — so this still needs no API key
 * for either provider.
 */
@ActiveProfiles("test")
@SpringBootTest
class AuraApplicationIT extends PostgresBackedContext {

    @Test
    void contextLoads() {
    }

}
