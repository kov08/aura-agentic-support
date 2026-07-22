package org.aura.aura.resolver;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The resolver's output contract with the model — the Day 6 classifier pattern applied to the
 * resolve path. {@code outputConfig(Class<T>)} derives a JSON schema from this record and the API
 * enforces it server-side, so the customer reply and the escalation verdict arrive as ONE atomic,
 * shape-guaranteed answer instead of prose we would have to infer intent from.
 *
 * <p><b>Only model JUDGMENTS belong here.</b> {@code sourcesUsed} is deliberately absent: it is a
 * deterministic retrieval receipt that {@link ResolverService} derives from the {@link KbEntry} hits
 * it actually retrieved. Moving it into this schema would demote it to a model self-report the model
 * could fabricate — and the eval's {@code expectedSources} check would then be grading a claim about
 * retrieval rather than retrieval itself, hiding the exact naive-keyword whiff it exists to expose.
 *
 * <p><b>Component order is wire order.</b> Record component order = generated schema property order
 * = the order the model streams its fields. {@code reply} MUST stay first: the SSE path
 * ({@link org.aura.aura.streaming.StreamingReplyExtractor}) forwards the reply's characters as they
 * arrive, so any field generated ahead of it would delay the customer's first visible character by
 * however long that field takes to emit. Reordering these components is a customer-visible latency
 * change, not a cosmetic one.
 */
public record ResolverOutput(

        @JsonPropertyDescription("The complete message to send to the customer: warm, direct, at most three short paragraphs, no internal notes, labels, or JSON")
        String reply,

        @JsonPropertyDescription("True when this ticket must go to a human agent, judged by the <escalation> criteria in the system prompt; whenever this is true the reply itself must say so plainly")
        boolean escalate
) {}
