package org.aura.aura.resolver;

import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.cache.CacheKeyFactory;
import org.aura.aura.cache.ResolutionCache;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cache-aside policy for {@link CachedResolutionService} (ADR-018). Each test states one sentence of
 * that policy; the LLM is never touched — the resolver itself is mocked at the bean boundary.
 */
@ExtendWith(MockitoExtension.class)
class CachedResolutionServiceTest {

    private static final String TICKET = "How long do I have to return something?";
    private static final ResolveTicketRequest REQUEST = new ResolveTicketRequest(TICKET);
    private static final String KEY = "aura:resolution:v1:deadbeef";

    @Mock CacheKeyFactory keys;
    @Mock ResolutionCache cache;
    @Mock ResolverService resolver;
    @Mock ResolverPromptProvider prompts;

    @InjectMocks CachedResolutionService service;

    // POLICY: a cache HIT returns the stored resolution and never calls the resolver — the whole point,
    // and the outage guarantee (OPEN breaker + hit => real answer) rides on the resolver being untouched.
    @Test
    void returnsCachedResolutionWithoutCallingResolver() {
        Resolution cached = new Resolution("cached answer", List.of("kb-returns"), ResolutionStatus.RESOLVED, false);
        when(keys.resolutionKey(any(), any(), any(), anyDouble(), anyLong())).thenReturn(KEY);
        when(cache.get(KEY)).thenReturn(Optional.of(cached));

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(resolver);
    }

    // POLICY: a MISS calls the resolver exactly once and stores the fresh, non-degraded answer.
    @Test
    void callsResolverOnceAndStoresOnMiss() {
        Resolution fresh = new Resolution("fresh answer", List.of("kb-returns"), ResolutionStatus.RESOLVED, false);
        when(keys.resolutionKey(any(), any(), any(), anyDouble(), anyLong())).thenReturn(KEY);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET)).thenReturn(fresh);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(fresh);
        verify(resolver, times(1)).resolve(TICKET);
        verify(cache).put(KEY, fresh);
    }

    // POLICY: an ADR-014 escalation fallback is an availability answer, not knowledge — it is returned
    // to the caller but NEVER cached, or every ticket would keep escalating for the whole TTL after
    // Anthropic recovers.
    @Test
    void fallbackResolutionIsNeverCached() {
        Resolution escalated = new Resolution(
                "escalated to a human", List.of(), ResolutionStatus.ESCALATED_TO_HUMAN, true);
        when(keys.resolutionKey(any(), any(), any(), anyDouble(), anyLong())).thenReturn(KEY);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET)).thenReturn(escalated);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(escalated);
        verify(cache, never()).put(any(), any());
    }

    // POLICY (Day 10): a MODEL-CHOSEN escalation is still a knowledge answer and IS cached. This is
    // the distinction the two escalation channels exist to preserve — the gate keys off `status`
    // (dependency health), never off `escalate` (business judgment). Cache-skipping every escalation
    // would mean paying Sonnet again on every repeat of the tickets most likely to repeat, and the
    // same ticket deserves the same escalation tomorrow anyway.
    @Test
    void modelChosenEscalationIsCachedBecauseItIsAKnowledgeAnswer() {
        Resolution escalating = new Resolution(
                "I'm escalating this to a specialist.", List.of(), ResolutionStatus.RESOLVED, true);
        when(keys.resolutionKey(any(), any(), any(), anyDouble(), anyLong())).thenReturn(KEY);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET)).thenReturn(escalating);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(escalating);
        verify(cache).put(KEY, escalating);
    }
}
