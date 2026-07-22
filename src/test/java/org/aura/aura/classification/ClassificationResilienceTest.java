package org.aura.aura.classification;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the Day 8 resilience policy on {@link TicketClassificationService#classify}.
 * classify runs BEFORE resolve in every request, so a Claude outage that isn't contained here would
 * 5xx the whole request before {@link org.aura.aura.resolver.ResolverService}'s protection is reached.
 *
 * <p>Its policy deliberately DIFFERS from resolve's: classify has a safe degraded answer (human
 * review), so a transport failure degrades to that rather than propagating, and there is NO retry
 * (it's a cheap pre-gate). Only a PERMANENT error still surfaces. Same sliced Spring context as
 * {@link org.aura.aura.resolver.ResolverResilienceTest}, so the shared "anthropicApi" breaker and the
 * AOP proxy are real.
 */
@SpringBootTest(
        classes = ClassificationResilienceTest.ResilienceTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ClassificationResilienceTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({TicketClassificationService.class, ClassifierPromptProvider.class})
    static class ResilienceTestConfig {
        @Bean
        AnthropicClient anthropicClient() {
            return mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
        }
    }

    @Autowired
    AnthropicClient client;

    @Autowired
    TicketClassificationService classifier; // the AOP-proxied bean — @CircuitBreaker is live here

    @Autowired
    CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void resetSharedState() {
        reset(client);
        circuitBreakers.circuitBreaker("anthropicApi").reset();
    }

    // POLICY: a transport failure degrades to human review — it is NOT retried (one call), and it is
    // NOT propagated. This is the fix for the weakest-link problem: classify no longer 5xx's a request.
    @Test
    void degradesToHumanReviewOnTransportFailure() {
        when(client.messages().create(any(StructuredMessageCreateParams.class)))
                .thenThrow(new AnthropicIoException("simulated connection failure"));

        ClassificationResult result = classifier.classify("some ticket");

        assertThat(result.needsHumanReview()).isTrue();
        // Day 10: the REASON is the load-bearing assertion now. This run produced no model answer at
        // all, so the eval must exclude its labels as DEGRADED rather than score our fallback
        // constants — and DEPENDENCY_UNAVAILABLE is the only signal that says so.
        assertThat(result.reason()).isEqualTo(ReviewReason.DEPENDENCY_UNAVAILABLE);
        // No retry on classify: exactly one attempt (contrast with resolve, which retries transient).
        verify(client.messages(), times(1)).create(any(StructuredMessageCreateParams.class));
    }

    // POLICY: a permanent error is NOT masked as human review — it propagates, so a real bug (e.g. a
    // broken API key → 400/401) surfaces instead of being silently relabelled a degraded answer forever.
    @Test
    void doesNotMaskBadRequestAsHumanReview() {
        when(client.messages().create(any(StructuredMessageCreateParams.class)))
                .thenThrow(badRequest());

        assertThatThrownBy(() -> classifier.classify("some ticket"))
                .isInstanceOf(BadRequestException.class);

        verify(client.messages(), times(1)).create(any(StructuredMessageCreateParams.class));
    }

    // POLICY: when the SHARED breaker is open, classify fast-fails to human review without a network
    // call — the same breaker resolve() escalates on, driven open here directly via the registry.
    @Test
    void degradesToHumanReviewWhenCircuitOpen() {
        circuitBreakers.circuitBreaker("anthropicApi").transitionToOpenState();

        ClassificationResult result = classifier.classify("some ticket");

        assertThat(result.needsHumanReview()).isTrue();
        assertThat(result.reason()).isEqualTo(ReviewReason.DEPENDENCY_UNAVAILABLE);
        verify(client.messages(), never()).create(any(StructuredMessageCreateParams.class));
    }

    private static BadRequestException badRequest() {
        return BadRequestException.builder()
                .headers(Headers.builder().build())
                .body(JsonValue.from("bad_request"))
                .build();
    }
}
