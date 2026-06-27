package org.aura.aura;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
public class ResolverPromptProvider {

    private final String systemPrompt;

    public ResolverPromptProvider(
            @Value("classpath:prompts/resolver_system_prompt.md") Resource promptResource
    ) throws IOException {
        try (InputStream in = promptResource.getInputStream()) {
            this.systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }
}
