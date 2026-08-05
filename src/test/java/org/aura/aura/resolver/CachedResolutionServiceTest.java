package org.aura.aura.resolver;

import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.cache.CacheKeyFactory;
import org.aura.aura.cache.ResolutionCache;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.RetrievalService;
import org.aura.aura.retrieval.SourceRef;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cache-aside policy for {@link CachedResolutionService} (ADR-018 + Day 14's Decision 4). Each test
 * states one sentence of that policy; the LLM is never touched — the resolver itself is mocked at the
 * bean boundary.
 */
@ExtendWith(MockitoExtension.class)
class CachedResolutionServiceTest {

    private static final String TICKET = "How long do I have to return something?";
    private static final ResolveTicketRequest REQUEST = new ResolveTicketRequest(TICKET);
    private static final String KEY = "aura:resolution:v2:deadbeef";

    private static final ContextBlock CONTEXT = new ContextBlock(
            "<documents>\n<document id=\"x\" breadcrumb=\"Refund Policy\">30 days</document>\n</documents>",
            List.of(new SourceRef(UUID.randomUUID(), "Refund Policy", 0.19)));

    @Mock RetrievalService retrieval;
    @Mock CacheKeyFactory keys;
    @Mock ResolutionCache cache;
    @Mock ResolverService resolver;
    @Mock ResolverPromptProvider prompts;

    @InjectMocks CachedResolutionService service;

    // POLICY (Decision 4): retrieval happens BEFORE the key is computed, because the retrieved bytes
    // are one of the key's inputs. This ordering is the entire day's cache work — get it backwards and
    // the key is blind to KB edits again, which is the defect the reposition exists to fix.
    @Test
    void retrievesBeforeComputingTheKey() {
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.of(resolution("cached", ResolutionStatus.RESOLVED)));

        service.resolve(REQUEST);

        InOrder order = inOrder(retrieval, keys, cache);
        order.verify(retrieval).retrieve(TICKET);
        order.verify(keys).resolutionKey(any(), any(), anyInt(), any(), any(), any(), anyDouble(), anyLong());
        order.verify(cache).get(KEY);
    }

    // POLICY: the RETRIEVED BYTES are what gets keyed. Not the chunk ids, not a count, not the query
    // vector — the rendered block, verbatim. This is what makes a KB edit self-invalidating.
    @Test
    void keysOnTheRenderedContextBlock() {
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.of(resolution("cached", ResolutionStatus.RESOLVED)));

        service.resolve(REQUEST);

        verify(keys).resolutionKey(eq(ResolverService.MODEL_ID), any(), anyInt(), any(),
                eq(CONTEXT.rendered()), eq(TICKET), eq(ResolverService.TEMPERATURE),
                eq(ResolverService.MAX_TOKENS));
    }

    // POLICY: a cache HIT returns the stored resolution and never calls the resolver — the whole point,
    // and the outage guarantee (OPEN breaker + hit => real answer) rides on the resolver being untouched.
    //
    // Day 14 narrowed what "free" means here: a hit still pays retrieval (one Voyage query embedding
    // plus one pgvector search) before it can discover it is a hit. What it still skips is the
    // expensive call — Sonnet — which is where the money was.
    @Test
    void returnsCachedResolutionWithoutCallingResolver() {
        Resolution cached = resolution("cached answer", ResolutionStatus.RESOLVED);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.of(cached));

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(cached);
        verifyNoInteractions(resolver);
    }

    // POLICY: a MISS calls the resolver exactly once and stores the fresh, non-degraded answer.
    @Test
    void callsResolverOnceAndStoresOnMiss() {
        Resolution fresh = resolution("fresh answer", ResolutionStatus.RESOLVED);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(fresh);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(fresh);
        verify(resolver, times(1)).resolve(TICKET, CONTEXT);
        verify(cache).put(KEY, fresh);
    }

    // POLICY: the resolver is handed the SAME context object the key was computed from. Retrieving a
    // second time inside the resolver would not merely waste a billable embedding — the corpus is
    // live and embeddings are not bit-reproducible, so the second result could differ, and the entry
    // would then be stored under a key describing documents the model never saw.
    @Test
    void sendsTheResolverExactlyTheContextThatWasKeyed() {
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(resolution("fresh", ResolutionStatus.RESOLVED));

        service.resolve(REQUEST);

        verify(retrieval, times(1)).retrieve(TICKET);   // exactly once for the whole request
        verify(resolver).resolve(TICKET, CONTEXT);
    }

    // POLICY: an ADR-014 escalation fallback is an availability answer, not knowledge — it is returned
    // to the caller but NEVER cached, or every ticket would keep escalating for the whole TTL after
    // Anthropic recovers.
    @Test
    void fallbackResolutionIsNeverCached() {
        Resolution escalated = new Resolution(
                "escalated to a human", List.of(), ResolutionStatus.ESCALATED_TO_HUMAN, true);
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(escalated);

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
        stubKey();
        when(retrieval.retrieve(TICKET)).thenReturn(CONTEXT);
        when(cache.get(KEY)).thenReturn(Optional.empty());
        when(resolver.resolve(TICKET, CONTEXT)).thenReturn(escalating);

        Resolution result = service.resolve(REQUEST);

        assertThat(result).isSameAs(escalating);
        verify(cache).put(KEY, escalating);
    }

    // ---------------------------------------------------------------- fixtures

    private void stubKey() {
        when(keys.resolutionKey(any(), any(), anyInt(), any(), any(), any(), anyDouble(), anyLong()))
                .thenReturn(KEY);
    }

    private static Resolution resolution(String answer, ResolutionStatus status) {
        return new Resolution(answer, CONTEXT.sourcesProvided(), status, false);
    }
}
