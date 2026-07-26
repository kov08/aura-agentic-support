package org.aura.aura.resilience;

import com.anthropic.errors.AnthropicInvalidDataException;

import java.io.InterruptedIOException;

/**
 * Classifies one narrow, ambiguous Anthropic SDK failure as transient (Day 11).
 *
 * <p>When a client-side timeout fires mid-response-read, the SDK does NOT throw {@code AnthropicIoException}
 * (already on the transient allowlist) — it throws {@link AnthropicInvalidDataException} with the message
 * "Error reading response", whose root cause is an {@link InterruptedIOException} (OkHttp's call timeout)
 * or a {@code java.net.SocketTimeoutException} (a subclass of it). That is a HUNG DEPENDENCY: it should
 * degrade to a human, exactly like a 429 or a 5xx.
 *
 * <p>But {@code AnthropicInvalidDataException} is also thrown for a genuinely MALFORMED response body — a
 * parse error with no timeout in its cause chain. That is a data contract problem, not a dependency hang,
 * and must still fail loud (surface as a 5xx) rather than be masked as an outage escalation — the same
 * "fail closed on the unknown" spirit as the ADR-012/013 allowlist. So we cannot add the whole exception
 * TYPE to the allowlist; we discriminate on the cause chain instead.
 *
 * <p>This is deliberately used only by the Resilience4j FALLBACKS (resolver + classifier), not added to
 * {@code retry-exceptions}: a hang is best failed-fast to a human, not retried three times at one timeout
 * each (which in prod would stack to ~90s of dead wait before escalating).
 */
public final class AnthropicTransientFailures {

    private AnthropicTransientFailures() {}

    /** True iff {@code t} is an {@link AnthropicInvalidDataException} caused by a read/call timeout. */
    public static boolean isReadTimeout(Throwable t) {
        if (!(t instanceof AnthropicInvalidDataException)) {
            return false;
        }
        // SocketTimeoutException extends InterruptedIOException, so this one check covers both the
        // OkHttp call-timeout ("timeout") and socket read-timeout ("Read timed out") shapes.
        for (Throwable cause = t.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
