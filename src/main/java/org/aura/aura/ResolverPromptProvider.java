package org.aura.aura;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The resolver's static system block: the exact text that sits BEFORE the ADR-020 {@code
 * cache_control} breakpoint, plus the version marker that identifies it.
 *
 * <h2>Day 14: where the grounding instruction lives</h2>
 * "Answer only from the provided documents; if they do not contain the answer, say so and escalate."
 * is appended to {@code resolver_system_prompt.md} itself — it is NOT a Java string concatenated on
 * afterwards. Two reasons, and the second is the one that matters.
 *
 * <p>ADR-007a: prompt text belongs in versioned data files, not in code. A grounding rule assembled
 * in Java would be prompt text that support and eval owners cannot see, review, or diff.
 *
 * <p>And it keeps the prompt SURFACE single. The version marker in that file is the identity the
 * eval harness stamps on every scored run and (Day 14) the response cache key folds in; splitting the
 * text across a file and a constant would mean a change to the constant moved the model's behaviour
 * while the version number stayed put, which is precisely the untraceable score movement the marker
 * exists to prevent. The block is byte-stable across requests, so it rides inside the cached prefix
 * at the 0.1x rate exactly like the rest of the file.
 */
@Component
public class ResolverPromptProvider {

    private static final String FILE = "resolver_system_prompt.md";

    private final String systemPrompt;
    private final int promptVersion;

    public ResolverPromptProvider(
            @Value("classpath:prompts/resolver_system_prompt.md") Resource promptResource
    ) throws IOException {
        try (InputStream in = promptResource.getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        this.promptVersion = PromptVersionMarker.parse(systemPrompt, FILE);
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * The prompt's declared version, parsed from the file rather than duplicated as a constant —
     * one source of truth, so the number in the file and the number stamped on an eval result can
     * never disagree.
     *
     * <p>Note the marker covers a surface WIDER than this file: the {@code @JsonPropertyDescription}
     * texts on {@link org.aura.aura.resolver.ResolverOutput} are injected into the schema the model
     * reads, which makes them prompt too. Editing one of those without bumping the marker here would
     * silently attribute a score movement to the wrong prompt version.
     */
    public int promptVersion() {
        return promptVersion;
    }

    /**
     * The prompt's stable NAME, as opposed to its version.
     *
     * <p>Exposed for the Day 14 cache key, which folds in {@code id@vN} alongside the prompt's actual
     * bytes. The id is not redundant with the bytes: it is what makes a key's composition readable to
     * a human debugging a cache miss, and it is what would distinguish two prompts that happened to
     * be at the same version number if AURA ever ran more than one resolver prompt.
     */
    public String promptId() {
        return FILE;
    }
}
