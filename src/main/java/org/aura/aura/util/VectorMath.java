package org.aura.aura.util;

/**
 * Vector similarity, the arithmetic half of semantic search.
 *
 * <p><b>Cosine vs dot product.</b> Voyage returns unit-normalized vectors — every embedding already
 * has length 1 — and for unit vectors cosine similarity and dot product are the same number, so
 * ranking by either produces an IDENTICAL ordering. We compute the full cosine anyway, magnitudes and
 * all. It is defensive: the normalization guarantee belongs to the provider, not to us, and if a
 * future model (or a locally-computed centroid, or an averaged vector) arrives un-normalized, the dot
 * product would silently rank by magnitude instead of by direction. The two extra square roots cost
 * nothing at the scale where this code runs, and tomorrow pgvector does the arithmetic anyway — this
 * class exists to make the in-memory demo honest, not to be fast.
 */
public final class VectorMath {

    private VectorMath() {}

    /**
     * @return cosine similarity in [-1, 1]; 0 when either vector is all zeros (an undefined angle,
     *         reported as "no similarity" rather than as NaN, which would poison every downstream sort)
     * @throws IllegalArgumentException if the vectors have different dimensions
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("cosineSimilarity requires two vectors; got a null");
        }
        if (a.length != b.length) {
            // The overwhelmingly likely cause of a dimension mismatch is a corpus embedded with one
            // model being compared against a query embedded with another, so the message names that
            // cause instead of only reporting the two numbers. Left as a raw length comparison, this
            // surfaces as an ArrayIndexOutOfBounds ten frames away from the actual mistake.
            throw new IllegalArgumentException(
                    "vector dimension mismatch: " + a.length + " vs " + b.length
                            + " — vectors from different models/dimensions are not comparable");
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
