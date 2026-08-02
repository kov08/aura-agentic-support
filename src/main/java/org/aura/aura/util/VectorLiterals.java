package org.aura.aura.util;

/**
 * The {@code float[]} ⇄ pgvector text-literal boundary: {@code [0.1,0.2,0.3]}.
 *
 * <p>It exists because the READ path and the WRITE path do not share a mechanism. Writing a chunk
 * goes through Hibernate, which knows the column is a {@code vector} and binds the array natively.
 * Querying by similarity goes through a native SQL query, where the parameter is just a bind
 * variable — there is no entity to hang a type on, so the vector has to arrive as pgvector's own text
 * form and be {@code CAST(? AS vector)} on the far side. This class is that one conversion, written
 * once, instead of a {@code StringBuilder} loop at each call site.
 *
 * <h2>The locale trap</h2>
 * A vector literal is parsed by Postgres with C semantics: {@code 0.1} is a number, {@code 0,1} is a
 * syntax error inside a comma-separated list — or worse, it silently doubles the element count and
 * the insert is rejected for the wrong reason. The obvious implementations — {@code String.format},
 * {@code DecimalFormat}, {@code NumberFormat} — all use {@link java.util.Locale#getDefault()}, so
 * this code is correct on a developer's machine in {@code en-US} and corrupt on the same code running
 * under {@code de-DE} or {@code fr-FR}. It is a defect that no amount of local testing finds, because
 * the default locale is ambient state that tests inherit rather than set.
 *
 * <p>The fix here is not to pass {@code Locale.ROOT} to a formatter but to use no formatter at all:
 * {@link Float#toString(float)} and {@link Float#parseFloat(String)} are specified against a fixed
 * grammar and are locale-independent by definition, and {@code Float.toString} additionally emits the
 * shortest decimal that round-trips to the same {@code float} — so the literal is both locale-proof
 * and lossless, which a {@code "%.6f"} format string would not be. {@code VectorLiteralsTest} pins
 * this by round-tripping under a comma-decimal default locale.
 */
public final class VectorLiterals {

    private VectorLiterals() {}

    /**
     * @param vector a non-null, non-empty embedding
     * @return the pgvector text form, e.g. {@code "[0.1,-0.2]"}, suitable for {@code CAST(? AS vector)}
     */
    public static String toLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            // pgvector rejects `[]` as a zero-dimension vector anyway; failing here names the actual
            // mistake instead of surfacing it as a SQL error several frames away.
            throw new IllegalArgumentException("cannot build a vector literal from an empty vector");
        }
        // Sized for ~12 characters per element plus the brackets — a starting guess that avoids most
        // of the reallocations on a 1024-element vector, not a bound.
        StringBuilder out = new StringBuilder(vector.length * 12 + 2);
        out.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) out.append(',');
            out.append(Float.toString(vector[i]));   // locale-independent by specification
        }
        return out.append(']').toString();
    }

    /**
     * The inverse of {@link #toLiteral}, for reading a {@code vector} column back through a path that
     * has no Hibernate type behind it (a raw {@code JdbcTemplate} query in a test or a diagnostic).
     *
     * @throws IllegalArgumentException if the text is not a bracketed, comma-separated vector literal
     */
    public static float[] fromLiteral(String literal) {
        if (literal == null) {
            throw new IllegalArgumentException("cannot parse a null vector literal");
        }
        String trimmed = literal.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || trimmed.charAt(trimmed.length() - 1) != ']') {
            throw new IllegalArgumentException(
                    "not a pgvector literal (expected a bracketed, comma-separated list): " + literal);
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("vector literal has no elements: " + literal);
        }

        // -1 as the limit keeps trailing empty fields ("[1,]") instead of dropping them, so a
        // malformed literal fails loudly in parseFloat rather than parsing as a shorter vector. A
        // silently shorter vector is exactly the failure this whole class is trying not to have.
        String[] parts = body.split(",", -1);
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].trim());   // locale-independent by specification
        }
        return vector;
    }
}
