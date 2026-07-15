package org.aura.aura.resolver;

import org.aura.aura.ResolverPromptProvider;
import org.aura.aura.cache.CacheKeyFactory;
import org.aura.aura.cache.ResolutionCache;
import org.aura.aura.web.dto.ResolveTicketRequest;
import org.springframework.stereotype.Service;

import java.util.Optional;

// ADR-018: cache-aside in front of the resolver. The prompt's "TicketResolutionService" is AURA's
// ResolverService; "TicketRequest" is ResolveTicketRequest; the cached "ResolutionResponse" is the
// domain Resolution.
@Service
public class CachedResolutionService {

    private final CacheKeyFactory keys;
    private final ResolutionCache cache;
    // Injected Spring bean => the call below crosses the AOP proxy, so Day 8's
    // @Retry/@CircuitBreaker stay live. If this logic sat INSIDE the resolver class and
    // used a plain `this.` call, the proxy would be bypassed and the resilience stack
    // would silently vanish while every client-mocking test still passed (Day 8 pitfall,
    // now made structurally impossible).
    private final ResolverService resolver;
    // The static system prompt (one of the four answer-affecting key inputs) is read HERE from the
    // shared provider, NOT from `resolver` — deliberately, so a cache HIT never touches the resolver
    // bean (that's what lets the outage path "OPEN + hit => real answer" hold, and what
    // verifyNoInteractions(resolver) asserts). Same provider ResolverService.paramsFor uses, so the
    // keyed prefix is byte-identical to the one that carries the cache_control breakpoint.
    private final ResolverPromptProvider prompts;

    public CachedResolutionService(CacheKeyFactory keys, ResolutionCache cache,
                                   ResolverService resolver, ResolverPromptProvider prompts) {
        this.keys = keys;
        this.cache = cache;
        this.resolver = resolver;
        this.prompts = prompts;
    }

    public Resolution resolve(ResolveTicketRequest request) {
        String ticketText = request.message();
        String key = keys.resolutionKey(
                ResolverService.MODEL_ID,
                prompts.systemPrompt(),
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

        Resolution fresh = resolver.resolve(ticketText);

        // ADR-014 fallbacks are availability answers, not knowledge answers: cache one
        // and we'd keep escalating tickets for the full TTL after Anthropic recovers.
        if (!fresh.isEscalatedFallback()) {     // the existing ADR-014 status marker
            cache.put(key, fresh);
        }
        return fresh;
    }
}
