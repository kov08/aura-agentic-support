package org.aura.aura.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup tripwires for the Voyage config, in the shape {@link org.aura.aura.AnthropicApiKeyFailFastTest}
 * established: {@link ApplicationContextRunner}, so only property binding + JSR-303 validation run.
 *
 * <p>The family-mismatch case is the one that earns its keep. A blank key fails loudly on the first
 * call anyway — the check just moves the failure earlier. A cross-family model pair never fails at
 * all: the requests succeed, cosine similarity returns real numbers, and the top-k results are
 * quietly wrong forever. A defect with no natural detector has to be caught by a deliberate one.
 */
class VoyagePropertiesFailFastTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableVoyageProps.class)
            .withPropertyValues("voyage.api-key=pa-test");   // the valid baseline each test varies

    @Test
    void blankApiKeyFailsContextStartupAndNamesTheEnvVar() {
        runner.withPropertyValues("voyage.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("keyless startup failure must name VOYAGE_API_KEY")
                            .hasStackTraceContaining("VOYAGE_API_KEY");
                });
    }

    @Test
    void crossFamilyModelPairRefusesToBoot() {
        // Legal-looking, individually valid model names — and a pairing that yields silently
        // meaningless similarity scores, because the two models do not share an embedding space.
        runner.withPropertyValues(
                        "voyage.document-model=voyage-4-large",
                        "voyage.query-model=voyage-3-lite")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("same voyage-4 family");
                });
    }

    @Test
    void sameFamilyAsymmetricPairIsTheSupportedCase() {
        // Two DIFFERENT models is the whole design, not an accident — it is only the family boundary
        // that is fixed. This test exists so nobody "fixes" the assertion into an equality check.
        runner.withPropertyValues(
                        "voyage.document-model=voyage-4-large",
                        "voyage.query-model=voyage-4-lite")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    VoyageProperties props = context.getBean(VoyageProperties.class);
                    assertThat(props.documentModel()).isNotEqualTo(props.queryModel());
                });
    }

    @Test
    void overlapAtOrAboveTheChunkCapRefusesToBoot() {
        runner.withPropertyValues("voyage.max-chunk-chars=500", "voyage.overlap-chars=500")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("overlap-chars must be smaller");
                });
    }

    @Test
    void defaultsApplyWhenOnlyTheKeyIsSupplied() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            VoyageProperties props = context.getBean(VoyageProperties.class);
            assertThat(props.baseUrl()).isEqualTo("https://api.voyageai.com");
            assertThat(props.documentModel()).isEqualTo("voyage-4-large");
            assertThat(props.queryModel()).isEqualTo("voyage-4-lite");
            assertThat(props.maxChunkChars()).isEqualTo(2000);
            assertThat(props.overlapChars()).isEqualTo(300);
        });
    }

    @EnableConfigurationProperties(VoyageProperties.class)
    @Configuration(proxyBeanMethods = false)
    static class EnableVoyageProps {
    }
}
