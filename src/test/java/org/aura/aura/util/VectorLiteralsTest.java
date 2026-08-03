package org.aura.aura.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VectorLiterals} is eleven lines of string building, and it gets a test class because the one
 * way it can be wrong is invisible on the machine that wrote it.
 */
class VectorLiteralsTest {

    private final Locale originalDefault = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        // The default locale is JVM-global mutable state. Leaking a comma-decimal locale out of this
        // class would give some unrelated test a failure with no plausible cause, which is a worse
        // outcome than the bug being tested for.
        Locale.setDefault(originalDefault);
    }

    @Test
    void producesThePgvectorTextForm() {
        assertThat(VectorLiterals.toLiteral(new float[]{0.1f, -0.2f, 3.0f}))
                .isEqualTo("[0.1,-0.2,3.0]");
    }

    @Test
    void roundTripsExactly() {
        float[] original = {0.1f, -0.2f, 3.0f, 1.0e-8f, -7.25f, 0.0f};

        assertThat(VectorLiterals.fromLiteral(VectorLiterals.toLiteral(original)))
                .as("a vector must survive the text boundary element-for-element — a lossy format "
                        + "here would perturb every similarity score by an amount nothing measures")
                .containsExactly(original);
    }

    /**
     * THE test this class exists for.
     *
     * <p>Under a comma-decimal locale, {@code String.format("%f", 0.1f)} yields {@code "0,1"} — which
     * inside a comma-separated vector literal does not fail, it parses as TWO elements. A 1024-element
     * vector becomes a 2048-element one and Postgres rejects it with a dimension error that names the
     * wrong problem entirely, sending whoever debugs it to look at the embedding model.
     *
     * <p>These locales are not exotic: de-DE, fr-FR, es-ES, pt-BR, it-IT, and most of Europe and South
     * America format decimals this way. The defect ships the moment the application runs anywhere the
     * developer did not.
     */
    @ParameterizedTest
    @ValueSource(strings = {"de-DE", "fr-FR", "pt-BR"})
    void isImmuneToTheDefaultLocalesDecimalSeparator(String languageTag) {
        Locale.setDefault(Locale.forLanguageTag(languageTag));

        float[] original = {0.1f, -0.25f, 1234.5f};
        String literal = VectorLiterals.toLiteral(original);

        assertThat(literal)
                .as("a comma may only ever be an element separator, never a decimal point")
                .isEqualTo("[0.1,-0.25,1234.5]");
        assertThat(VectorLiterals.fromLiteral(literal)).containsExactly(original);
    }

    @Test
    void handlesAFullSizeEmbedding() {
        float[] vector = new float[1024];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = i / 1024.0f;
        }

        float[] parsed = VectorLiterals.fromLiteral(VectorLiterals.toLiteral(vector));

        assertThat(parsed).hasSize(1024).containsExactly(vector);
    }

    @Test
    void rejectsAnEmptyVector() {
        assertThatThrownBy(() -> VectorLiterals.toLiteral(new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty vector");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1,0.2", "[0.1,0.2", "0.1,0.2]", "[]", "   "})
    void rejectsTextThatIsNotAVectorLiteral(String malformed) {
        assertThatThrownBy(() -> VectorLiterals.fromLiteral(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToSilentlyShortenAMalformedLiteral() {
        // "[1,2,]" could plausibly be read as a 2-element vector. It is not: a trailing separator means
        // the producer is broken, and inferring a shorter vector from broken input is how a dimension
        // mismatch becomes a mystery instead of an error.
        assertThatThrownBy(() -> VectorLiterals.fromLiteral("[1,2,]"))
                .isInstanceOf(NumberFormatException.class);
    }
}
