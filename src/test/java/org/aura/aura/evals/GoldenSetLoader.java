package org.aura.aura.evals;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads {@code golden-set-v2.json} from the test classpath. Shared by {@link GoldenSetIntegrityTest}
 * (which asserts it is well-formed) and {@link EvalRunner} (which runs it) so both read the exact
 * same file through the exact same mapper — one source, no drift between what we validate and what
 * we score.
 */
public final class GoldenSetLoader {

    public static final String RESOURCE = "/evals/golden-set-v2.json";

    // A plain Jackson 3 mapper. Records deserialize natively; an illegal enum value (a renamed
    // category, a typo'd urgency) throws HERE, which is exactly the loud failure the integrity test
    // is built to surface rather than quietly accepting a rotten label.
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private GoldenSetLoader() {}

    public static GoldenSet load() {
        try (InputStream in = GoldenSetLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("golden set not found on classpath: " + RESOURCE);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readValue(json, GoldenSet.class);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("failed reading " + RESOURCE, e);
        }
    }
}
