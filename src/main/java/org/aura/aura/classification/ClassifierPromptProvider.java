package org.aura.aura.classification;

import org.aura.aura.PromptVersionMarker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// Same externalized-prompt pattern as ResolverPromptProvider: the prompt lives as a
// classpath resource so prompt edits are content changes (reviewable in diffs, no
// recompile of logic), and loading once at startup fails fast if the file is missing.
@Component
public class ClassifierPromptProvider {

    private static final String FILE = "classifier_system_prompt.md";

    private final String systemPrompt;
    private final int promptVersion;

    public ClassifierPromptProvider(
            @Value("classpath:prompts/classifier_system_prompt.md") Resource promptResource
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
     * Still version 1 (Day 6) and deliberately so: Day 10 changed how this service REPORTS a fallback
     * (see {@link ReviewReason}), not anything the model reads. The version tracks prompt surface, and
     * bumping it for a code-only change would falsely imply the classifier's judgment had shifted.
     */
    public int promptVersion() {
        return promptVersion;
    }
}
