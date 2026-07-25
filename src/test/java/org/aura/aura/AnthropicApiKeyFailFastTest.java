package org.aura.aura;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fail-fast tripwire for a missing API key. A blank {@code aura.anthropic.api-key} must fail the
 * context at binding time — no full boot, no timing — and the failure must name {@code ANTHROPIC_API_KEY}
 * so a keyless operator knows exactly what to set. Uses {@link ApplicationContextRunner} so only the
 * property binding + JSR-303 validation run (hibernate-validator is on the test classpath).
 */
class AnthropicApiKeyFailFastTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableAnthropicProps.class);

    @Test
    void blankApiKeyFailsContextStartupAndNamesTheEnvVar() {
        runner.withPropertyValues("aura.anthropic.api-key=")   // blank — the missing-key case
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("keyless startup failure must name ANTHROPIC_API_KEY")
                            .hasStackTraceContaining("ANTHROPIC_API_KEY");
                });
    }

    @Test
    void presentApiKeyBindsCleanly() {
        runner.withPropertyValues("aura.anthropic.api-key=sk-ant-anything")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AnthropicProperties.class).apiKey()).isEqualTo("sk-ant-anything");
                });
    }

    @EnableConfigurationProperties(AnthropicProperties.class)
    @Configuration(proxyBeanMethods = false)
    static class EnableAnthropicProps {
    }
}
