package org.aura.aura.evals;

import java.util.List;

/**
 * The graded outcome of one ticket — every field result the report needs, computed by
 * {@link EvalScorer} from the label and the two live service responses.
 *
 * <p>DEGRADED is per STAGE, not per ticket (amendment 8): a Resilience4j fallback from the classifier
 * marks {@code classifierDegraded}, one from the resolver marks {@code resolverDegraded}, and a
 * degraded stage's fields are excluded from scoring while the OTHER stage is still graded. The
 * matching-result fields are boxed {@link Boolean} precisely so a degraded (or not-applicable) field
 * can be {@code null} — "not graded" — rather than a misleading {@code false}.
 *
 * <p>ERRORED tickets never reach here: an exception mid-ticket is an our-side failure the runner
 * buckets directly, distinct from DEGRADED (a dependency being down).
 */
public record TicketScore(
        String ticketId,
        String slice,
        boolean classifierDegraded,
        boolean resolverDegraded,

        // Classifier stage — null when classifierDegraded.
        Boolean categoryMatch,
        Boolean urgencyMatch,
        Boolean intentMatch,

        // Resolver stage.
        boolean schemaValid,          // always true for a scored ticket: resolve() threw otherwise (→ ERRORED)
        Boolean escalateMatch,        // null when resolverDegraded
        SourcesResult sourcesResult,  // grade NOT_GRADED when expectedSources==null or resolverDegraded
        List<RuleViolation> mustContainMisses,   // required fragments ABSENT from the reply
        List<RuleViolation> mustNotViolations,   // forbidden fragments PRESENT, with surrounding context

        boolean passed                // all APPLICABLE assertions passed (a degraded stage is vacuously ok)
) {

    /** True when BOTH stages degraded — the ticket has no graded assertion at all and is excluded. */
    public boolean fullyDegraded() {
        return classifierDegraded && resolverDegraded;
    }

    /**
     * One outcome grade → resolved sources match, extra citations that only warn, or NOT graded.
     *
     * @param reason why the dimension was not graded, empty when it was. A NOT_GRADED with no
     *               explanation is indistinguishable from a dimension nobody bothered to implement —
     *               and Day 14 made that distinction load-bearing, because the sources dimension is
     *               now QUARANTINED rather than merely unlabelled. A reader of a results file has to
     *               be able to tell "this ticket carried no label" from "this dimension is switched
     *               off pending a relabel", and the only way to tell them apart is to say so.
     */
    public record SourcesResult(Grade grade, List<String> missing, List<String> extra, String reason) {
        public enum Grade { NOT_GRADED, PASS, FAIL }

        public boolean isFailure() {
            return grade == Grade.FAIL;
        }

        /** A subset PASS that carries unexpected extra citations — printed as a warning, never a failure. */
        public boolean hasWarnings() {
            return grade == Grade.PASS && !extra.isEmpty();
        }

        static SourcesResult notGraded() {
            return notGraded("");
        }

        static SourcesResult notGraded(String reason) {
            return new SourcesResult(Grade.NOT_GRADED, List.of(), List.of(), reason);
        }

        /** True when this dimension was switched off deliberately, as opposed to merely unlabelled. */
        public boolean isQuarantined() {
            return grade == Grade.NOT_GRADED && !reason.isEmpty();
        }
    }

    /**
     * A rule hit. For a mustContain MISS the {@code fragment} is the required substring that was
     * absent, and {@code context} is empty (there is nothing to show — it isn't there). For a mustNot
     * VIOLATION the {@code fragment} is what matched and {@code context} is ~60 chars of surrounding
     * reply text, so the report shows WHERE the forbidden phrase landed.
     */
    public record RuleViolation(String fragment, String context) {}
}
