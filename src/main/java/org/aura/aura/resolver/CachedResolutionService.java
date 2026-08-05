package org.aura.aura.resolver;

import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.cache.CacheKeyFactory;
import org.aura.aura.cache.ResolutionCache;
import org.aura.aura.retrieval.ContextBlock;
import org.aura.aura.retrieval.RetrievalService;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.springframework.stereotype.Service;

import java.util.Optional;

// ADR-018: cache-aside in front of the resolver. The prompt's "TicketResolutionService" is AURA's
// ResolverService; "TicketRequest" is ResolveTicketRequest; the cached "ResolutionResponse" is the
// domain Resolution.
@Service
public class CachedResolutionService {

    private final RetrievalService retrieval;
    private final CacheKeyFactory keys;
    private final ResolutionCache cache;
    // Injected Spring bean => the call below crosses the AOP proxy, so Day 8's
    // @Retry/@CircuitBreaker stay live. If this logic sat INSIDE the resolver class and
    // used a plain `this.` call, the proxy would be bypassed and the resilience stack
    // would silently vanish while every client-mocking test still passed (Day 8 pitfall,
    // now made structurally impossible).
    private final ResolverService resolver;
    // The static system prompt (one of the answer-affecting key inputs) is read HERE from the
    // shared provider, NOT from `resolver` — deliberately, so a cache HIT never touches the resolver
    // bean (that's what lets the outage path "OPEN + hit => real answer" hold, and what
    // verifyNoInteractions(resolver) asserts). Same provider ResolverService.paramsFor uses, so the
    // keyed prefix is byte-identical to the one that carries the cache_control breakpoint.
    private final ResolverPromptProvider prompts;

    public CachedResolutionService(RetrievalService retrieval, CacheKeyFactory keys,
                                   ResolutionCache cache, ResolverService resolver,
                                   ResolverPromptProvider prompts) {
        this.retrieval = retrieval;
        this.keys = keys;
        this.cache = cache;
        this.resolver = resolver;
        this.prompts = prompts;
    }

    public Resolution resolve(ResolveTicketRequest request) {
        String ticketText = request.message();

        // RETRIEVE FIRST — the Day 14 reordering, and the single most consequential line in this
        // method (Decision 4). The key cannot be computed before this call, because the retrieved
        // document bytes are one of its inputs; see CacheKeyFactory for why keying on anything less
        // means a KB edit keeps serving pre-edit answers for a full TTL.
        //
        // WHAT THIS COSTS, stated rather than buried: a cache HIT is no longer free. It now pays one
        // Voyage query embedding and one pgvector search before it can even discover that it is a
        // hit. That is the price of self-invalidation and it is worth it — the expensive call is
        // Sonnet, and a hit still skips that entirely — but the "hit costs nothing" property from
        // Day 9 is gone and nobody should be surprised by it later.
        //
        // WHAT THIS ALSO CHANGES: retrieval sits OUTSIDE the Resilience4j stack, which only wraps
        // resolver.resolve. So a Voyage or Postgres failure now propagates as a 5xx rather than
        // degrading to the ADR-014 human escalation — including on tickets that would have been cache
        // hits. Flagged, not fixed: giving retrieval its own fallback is a real design decision about
        // whether an ungrounded answer beats no answer, and it deserves its own day rather than being
        // smuggled in here.
        ContextBlock context = retrieval.retrieve(ticketText);

        String key = keys.resolutionKey(
                ResolverService.MODEL_ID,
                prompts.promptId(),
                prompts.promptVersion(),
                prompts.systemPrompt(),
                context.rendered(),
                ticketText,
                ResolverService.TEMPERATURE,
                ResolverService.MAX_TOKENS);

        // ORDER IS THE DESIGN (ADR-018): the cache is checked BEFORE the resilience
        // stack. A hit is not a call to Anthropic — it must not occupy a slot in the
        // breaker's sliding window, and it must not be BLOCKED when the breaker is OPEN.
        // Outage behavior: OPEN + hit => real answer; OPEN + miss => ADR-014 escalation.
        // The cache is an availability layer wearing a cost costume.
        Optional<Resolution> hit = cache.get(key);
        if (hit.isPresent()) return hit.get();

        // The SAME context object that produced the key is what gets sent. Retrieving a second time
        // inside the resolver would not merely waste a billable embedding — it could return a
        // different result (the corpus is live, and embeddings are not bit-reproducible), and then
        // the entry would be stored under a key describing documents the model never saw.
        Resolution fresh = resolver.resolve(ticketText, context);

        // ADR-014 fallbacks are availability answers, not knowledge answers: cache one
        // and we'd keep escalating tickets for the full TTL after Anthropic recovers.
        if (!fresh.isEscalatedFallback()) {     // the existing ADR-014 status marker
            cache.put(key, fresh);
        }
        return fresh;
    }

}
