package org.aura.aura;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class AuraApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuraApplication.class, args);
    }

    // A CommandLineRunner bean runs once, right after the Spring context is ready,
    // so this is the simplest place to fire a one-off "is the wiring alive?" smoke test
    // without yet introducing service/controller layers.
    @Bean
    public CommandLineRunner claudeSmokeTest() {
        return args -> {
            // fromEnv() reads ANTHROPIC_API_KEY (and any other ANTHROPIC_* config) from the
            // environment. The key is never hardcoded — keeping it out of source means it
            // can't leak via git and can differ per machine/CI without code changes.
            AnthropicClient client = AnthropicOkHttpClient.fromEnv();

            // try/finally guarantees the underlying OkHttp client (connection pool + thread
            // pool) is released even if the API call throws, so the JVM can shut down cleanly.
            try {
                // The Messages API is fully stateless: every call must carry the entire
                // conversation. Here that's a single user turn — there's no server-side
                // session to attach to, so the request is self-contained by design.
                MessageCreateParams params = MessageCreateParams.builder()
                        // Haiku is the cheapest/fastest tier — ideal for a connectivity probe
                        // where we only care that the round-trip works, not about reasoning depth.
                        .model(Model.CLAUDE_HAIKU_4_5)
                        // maxTokens caps the *output*. A 100-token ceiling is plenty for the
                        // three-word reply we ask for and prevents a runaway/expensive response.
                        .maxTokens(100L)
                        // One user message; we instruct an exact reply so the printed output is
                        // trivially verifiable by eye ("did it come back saying AURA online?").
                        .addUserMessage("Reply with exactly \"AURA online.\" and nothing else.")
                        .build();

                // Synchronous (blocking) call — create() returns only once the full response
                // is in hand. No streaming, because a smoke test wants the whole answer at once.
                Message response = client.messages().create(params);

                // content() is a list of typed blocks (text, thinking, tool_use, ...). We filter
                // to text blocks via the Optional-returning .text() accessor so a non-text block
                // can't throw, then print each text segment.
                response.content().stream()
                        .flatMap(block -> block.text().stream())
                        .forEach(text -> System.out.println("Claude says: " + text.text()));

                // The usage block is the canonical source for billing/observability. We surface
                // input vs output token counts here so the smoke test doubles as a sanity check
                // that token accounting is flowing through correctly.
                System.out.println("Input tokens:  " + response.usage().inputTokens());
                System.out.println("Output tokens: " + response.usage().outputTokens());
            } finally {
                // Explicitly close so the test doesn't leave OkHttp daemon threads lingering
                // after the runner returns.
                client.close();
            }
        };
    }

}