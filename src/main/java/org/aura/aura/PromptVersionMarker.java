package org.aura.aura;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code # version: N} marker that every prompt file carries in its header.
 *
 * <p>Shared by both prompt providers rather than duplicated, for the same reason the cache key reads
 * its inputs from one place (ADR-019): two copies of a parsing rule are two chances to disagree, and
 * a version number that disagrees with itself is worse than no version number — it attributes
 * results to a prompt that never produced them.
 *
 * <p>The version is read FROM the file instead of being declared as a Java constant beside it, so
 * there is exactly one place to edit when a prompt changes and no way to bump one without the other.
 */
public final class PromptVersionMarker {

    private static final Pattern MARKER = Pattern.compile("(?m)^#\\s*version:\\s*(\\d+)");

    private PromptVersionMarker() {}

    /**
     * @param prompt   the full prompt text
     * @param fileName only for the failure message — a missing marker should name the file to fix
     * @throws IllegalStateException if the marker is absent, which fails the app at STARTUP rather
     *         than mid-eval: discovering that results are untraceable halfway through a scored run
     *         wastes the entire run.
     */
    public static int parse(String prompt, String fileName) {
        Matcher marker = MARKER.matcher(prompt);
        if (!marker.find()) {
            throw new IllegalStateException(fileName + " is missing its '# version: N' marker");
        }
        return Integer.parseInt(marker.group(1));
    }
}
