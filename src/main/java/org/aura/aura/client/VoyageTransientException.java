package org.aura.aura.client;

/**
 * A Voyage failure that is worth trying again: rate limiting (429), request timeout (408), any 5xx,
 * and transport-level faults with no HTTP status at all (connect timeout, read timeout, connection
 * reset).
 *
 * <p>This type IS the retry policy's allowlist. {@code resilience4j.retry.instances.voyage} lists
 * exactly this class in {@code retry-exceptions}, so the decision "is this retryable?" is made once,
 * here at the mapping site where the HTTP status is still in hand — not re-derived later by a retry
 * layer inspecting a generic exception.
 */
public class VoyageTransientException extends RuntimeException {

    public VoyageTransientException(String message) {
        super(message);
    }

    public VoyageTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
