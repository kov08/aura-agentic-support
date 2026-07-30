package org.aura.aura.client;

/**
 * A Voyage failure that retrying cannot fix: a malformed request (400), a bad or missing key (401), an
 * input that exceeds the model's token limit (422), and every other client error not on the transient
 * list.
 *
 * <p>These are OUR bugs, not the provider's health. Retrying them burns the attempt budget, delays the
 * real error by the full backoff, and — on a 401 — turns one rejected key into three. Failing fast and
 * loudly is the correct behaviour, and it is what makes a chunk that is too long for the model surface
 * as a fixable 422 at ingestion time instead of a mysterious slow failure.
 */
public class VoyagePermanentException extends RuntimeException {

    public VoyagePermanentException(String message) {
        super(message);
    }

    public VoyagePermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}
