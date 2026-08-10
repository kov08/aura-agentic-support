package org.aura.aura.resolver;

/**
 * The model replied cleanly and we could not get a {@link ResolverOutput} out of it: no text block on
 * an {@code end_turn} response, or a payload that would not deserialize into the record.
 *
 * <h2>A distinct type, because it is retried and degraded differently from everything around it</h2>
 * Its neighbours are already spoken for. An {@code IllegalStateException} means "our bug" and is
 * deliberately absent from every allowlist so it surfaces as a 500 (that is the treatment a bad
 * {@code stop_reason} still gets — see {@link ResolverService#resolve}). The Anthropic SDK's transient
 * types mean "the dependency is unwell". This is neither: the dependency answered, promptly and with
 * a 200, and what it said was unusable.
 *
 * <p>So it gets its own row in the policy table, and the two halves of that policy sit in different
 * places on purpose:
 *
 * <ul>
 *   <li><b>Listed under {@code resilience4j.retry.instances.anthropicApi.retry-exceptions}</b> — the
 *       call is a pure read at temperature 1.0, so a second sample is a genuinely different draw and
 *       is very likely to parse. This is what the brief means by reusing the existing parse-retry
 *       behaviour rather than inventing a private loop inside the service.</li>
 *   <li><b>NOT listed under the breaker's {@code record-exceptions}</b>, and the omission is the
 *       decision. The breaker tracks the DEPENDENCY's health; a run of malformed generations says
 *       nothing about whether Anthropic is up. Recording them would let a bad prompt edit trip the
 *       breaker and take the classifier — which shares the instance — down with it.</li>
 * </ul>
 *
 * <p>Once retries are spent, {@code ResolverService.escalateToHuman} degrades it to
 * {@link EscalationCause#OUTPUT_UNUSABLE}: grounding cannot be checked on an answer that cannot be
 * read, and an answer whose grounding is unknown must not reach a customer. That is the one place
 * this differs from the Day 8 law of "degrade only on the dependency's problems" — and it differs
 * because the alternative is not "fail loud", it is "ship an unverified answer".
 */
public class ResolverOutputUnusableException extends RuntimeException {

    public ResolverOutputUnusableException(String message) {
        super(message);
    }

    public ResolverOutputUnusableException(String message, Throwable cause) {
        super(message, cause);
    }
}
