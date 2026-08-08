package org.aura.aura.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The retrieval canary: WHICH stored chunk is re-embedded at every boot, and WHAT range of measured
 * distance counts as healthy.
 *
 * <h2>Decision 1 — why a canary at all</h2>
 * {@code EmbeddingDimensionCheck} proves the vectors are the right WIDTH. Nothing proved they were in
 * the right SPACE. The Day 12 lab measured what that gap costs: point the query lane at a different
 * model era without re-embedding the corpus and every similarity score collapses toward random while
 * the request succeeds, the dimensions match, the ranking looks like a ranking, and the build stays
 * green. A width check cannot see it, because nothing about the width changed.
 *
 * <p>So the canary measures the one thing that would actually move: the distance between a
 * {@code voyage-4-large}/{@code document} vector that is already in the store and a freshly computed
 * {@code voyage-4-lite}/{@code query} vector of the identical text. That pairing is not a proxy for
 * the request path — it IS the request path, one chunk wide.
 *
 * <h2>Why the band is measured rather than assumed</h2>
 * The two lanes never agree exactly: different models, different input types, and a provider that
 * does not promise bit-identical results for repeated calls. So there is no constant to compare
 * against, and inventing one ("distance must be under 0.3") would be picking a threshold from taste
 * and then discovering its calibration in production. {@code CanaryBandHarnessIT} samples the real
 * noise floor and the band is derived from that sample by a rule fixed BEFORE the numbers existed —
 * see the harness, and see the provenance comment on {@code aura.canary.band} in application.yml.
 *
 * <h2>What used to be here, and why it is not (V4)</h2>
 * This record carried {@code sourceDoc} and {@code chunkIndex} — the identity of the canary's row in
 * {@code kb_chunks}. Both are gone. The probe now lives in {@code canary_probe}, a table whose
 * {@code CHECK (id = 1)} makes "there is exactly one probe" a schema fact, so there is nothing left to
 * address and no address left to configure.
 *
 * <p>That deletion is worth more than the two lines it saves. A guard that reads its own target out of
 * configuration can be pointed at the wrong thing by a config edit — which is precisely the class of
 * silent misconfiguration this guard exists to detect, reintroduced inside the guard itself. The
 * canary's text is a code constant ({@code CanaryDocument}) and its row is a primary key the schema
 * pins; neither can drift without a code review.
 */
@Validated
@ConfigurationProperties(prefix = "aura.canary")
public record CanaryProperties(

        /*
         * OFF unless configured on. The default direction matters: this bean's @ConditionalOnProperty
         * is what keeps the check — and its ChunkRepository dependency — out of every context that has
         * no database, the same mechanism IngestionPipeline uses. "Absent means off" is what makes a
         * slice test that knows nothing about canaries simply not have one.
         */
        boolean enabled,

        Band band,

        StoreProbe storeProbe
) {

    /**
     * The healthy interval for the measured distance, in pgvector's cosine-DISTANCE scale — smaller
     * is nearer, and that scale is the project-wide one (Decision 3A), so this number is directly
     * comparable to the distances that appear in {@code sourcesProvided}.
     *
     * <p>Boxed {@code Double}, not primitive, on purpose: the lower bound of a measured band can
     * legitimately be zero or negative, so a primitive's 0.0 default would make "never configured"
     * and "measured as 0.0" the same value. Absence has to stay representable for
     * {@link #isBandConfiguredWhenEnabled()} to have anything to check.
     */
    public record Band(Double minDistance, Double maxDistance) {

        public boolean contains(double distance) {
            return distance >= minDistance && distance <= maxDistance;
        }

        @Override
        public String toString() {
            return "[" + minDistance + ", " + maxDistance + "]";
        }
    }

    /**
     * The store-side probe: same-lane ({@code voyage-4-large}/{@code document}) fresh-vs-stored.
     * Off by default because it costs a premium-model embedding on every boot and answers a question
     * nobody is asking until the canary has already tripped.
     */
    public record StoreProbe(boolean enabled) {
    }

    public CanaryProperties {
        // Nested value objects bind to null when NOTHING under their prefix is set — which is the
        // normal state in the many test contexts that disable the canary outright. Defaulting them
        // here keeps every accessor non-null, so the validation below can be about "is this
        // configured coherently" rather than about null-checking.
        if (band == null) band = new Band(null, null);
        if (storeProbe == null) storeProbe = new StoreProbe(false);
    }

    @AssertTrue(message = "aura.canary.band.min-distance and max-distance must both be set when "
            + "aura.canary.enabled is true — the band is a MEASURED noise floor (run "
            + "CanaryBandHarnessIT), and a guard with no band to compare against would either pass "
            + "everything or fail everything")
    public boolean isBandConfiguredWhenEnabled() {
        return !enabled || (band.minDistance() != null && band.maxDistance() != null);
    }

    @AssertTrue(message = "aura.canary.band.min-distance must not exceed max-distance — an inverted "
            + "band admits nothing and would refuse every boot")
    public boolean isBandOrdered() {
        return band.minDistance() == null || band.maxDistance() == null
                || band.minDistance() <= band.maxDistance();
    }
}
