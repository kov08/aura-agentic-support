package org.aura.aura;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dry inspection (no boot, no network, no paid call) of the Day 11 profile split. The aggressive 500ms
 * timeout lives ONLY in application-it.yml, so:
 * <ul>
 *   <li>a context on {@code test} alone — what EvalRunner and its {@code -Pevals} LIVE calls use —
 *       resolves the prod-like 30s timeout from application.yml;</li>
 *   <li>the {@code *IT} classes, on {@code {test,it}}, get the fast 500ms.</li>
 * </ul>
 * Loads the real {@code application*.yml} via {@link ConfigDataApplicationContextInitializer} and reads
 * the resolved value — guarding the split from silently regressing (e.g. someone moving the fast timeout
 * back into {@code test}, which would time out every eval).
 */
class AnthropicProfileTimeoutResolutionTest {

    private ApplicationContextRunner runnerWithProfiles(String activeProfiles) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())  // loads application*.yml
                .withUserConfiguration(EnableAnthropicProps.class)
                .withPropertyValues("spring.profiles.active=" + activeProfiles);
    }

    @Test
    void evalProfile_testAlone_resolvesProdLikeTimeout() {
        runnerWithProfiles("test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AnthropicProperties.class).timeout())
                    .as("evals run on `test` alone and must keep the prod-like timeout")
                    .isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void integrationProfile_testAndIt_resolvesFastTimeout() {
        runnerWithProfiles("test,it").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AnthropicProperties.class).timeout())
                    .as("*IT classes add `it`, which shortens the timeout to milliseconds")
                    .isEqualTo(Duration.ofMillis(500));
        });
    }

    @EnableConfigurationProperties(AnthropicProperties.class)
    @Configuration(proxyBeanMethods = false)
    static class EnableAnthropicProps {
    }
}
