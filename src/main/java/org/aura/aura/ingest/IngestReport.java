package org.aura.aura.ingest;

import java.time.Duration;
import java.util.List;

/**
 * What one ingestion run actually did. Returned rather than only logged, for the reason
 * {@code CanaryProperties} and the old {@code LoadReport} were: a test that asserts on a record is
 * testing behaviour, and a test that greps log output is testing a string.
 *
 * <h2>{@code embeddingCalls} is the load-bearing field</h2>
 * The other counts describe the plan. This one describes what was SPENT, and it is the only number
 * that can prove the thing this whole day exists to deliver: run the pipeline twice over an unchanged
 * corpus and the second run reports zero. Idempotency stops being a claim about the design and
 * becomes an integer a test can assert on — which is why the DoD's third check is written against
 * this field and not against a log line or a row count.
 *
 * @param added          documents ingested for the first time
 * @param changed        documents whose chunks were rebuilt
 * @param unchanged      documents skipped because the fingerprint already matched — the free set
 * @param deleted        documents removed from the ledger, taking their chunks with them
 * @param failed         one entry per document that did not make it, with the reason. NOT an
 *                       exception: a document that fails must not stop the ones behind it, so the
 *                       failure is collected as data and reported at the end
 * @param duration       wall clock for the whole run
 * @param embeddingCalls HTTP calls made to Voyage. Counted per BATCH, so a document whose chunks fit
 *                       one request contributes one — and counted only on the attempt that
 *                       succeeded, so a retried call is billed twice by the provider and reported
 *                       once here. That understatement is deliberate: this number exists to prove
 *                       "we did no work", and a retry count would blur the zero it has to be able to
 *                       report
 */
public record IngestReport(int added,
                           int changed,
                           int unchanged,
                           int deleted,
                           List<Failure> failed,
                           Duration duration,
                           int embeddingCalls) {

    public IngestReport {
        failed = List.copyOf(failed);
    }

    /**
     * One document that did not make it through.
     *
     * @param path   the document, so the operator knows what to look at
     * @param reason the exception's type and message, flattened to a string at the catch site.
     *               Deliberately not the {@link Throwable} itself: this record is logged, asserted
     *               on, and may one day be serialised, and none of those want a stack trace's object
     *               graph. The trace is already in the log at ERROR, which is where a trace belongs
     */
    public record Failure(String path, String reason) {
    }

    /** True when nothing failed — the normal outcome, and the one worth being able to state simply. */
    public boolean isClean() {
        return failed.isEmpty();
    }

    /** Documents the plan intended to embed, whether or not they made it. */
    public int attempted() {
        return added + changed;
    }
}
