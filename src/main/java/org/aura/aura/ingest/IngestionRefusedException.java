package org.aura.aura.ingest;

/**
 * Thrown when a computed plan looks like an accident rather than an intention, and the pipeline
 * declines to carry it out.
 *
 * <h2>Why refusal is an exception and not a report</h2>
 * Every other outcome of a run — documents failing, nothing to do, a partial success — is data, and
 * comes back in an {@link IngestReport} so a caller can decide what it means. This one is different
 * in kind: the pipeline has concluded it cannot tell the difference between what it was asked to do
 * and a mistake, so there IS no outcome to report. Returning a report with a "refused" flag would
 * make ignoring it a matter of not reading a field, and the thing being guarded against is data loss.
 *
 * <p>Because the pipeline is triggered by an {@code ApplicationRunner}, an uncaught exception here
 * fails the boot. That is the intended blast radius: a startup that refuses is loud, recoverable, and
 * leaves the corpus exactly as it was.
 *
 * <p>Extends {@link IllegalStateException} to sit with the project's other fail-fast startup guards
 * ({@code EmbeddingDimensionCheck}, {@code RetrievalCanaryCheck}) rather than introducing a second
 * taxonomy for the same category of event. It is a distinct TYPE so that a test can assert the
 * refusal specifically, instead of matching on message text.
 */
public class IngestionRefusedException extends IllegalStateException {

    public IngestionRefusedException(String message) {
        super(message);
    }
}
