package org.aura.aura;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
}
