package org.aura.aura.classification;

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

    private final String systemPrompt;

    public ClassifierPromptProvider(
            @Value("classpath:prompts/classifier_system_prompt.md") Resource promptResource
    ) throws IOException {
        try (InputStream in = promptResource.getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }
}
