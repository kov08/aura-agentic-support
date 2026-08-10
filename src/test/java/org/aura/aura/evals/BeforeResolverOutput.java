package org.aura.aura.evals;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * The PRE-DAY-16 output schema, frozen — {@code ResolverOutput} as it stood on main before
 * {@code citations} and {@code grounded} existed.
 *
 * <p>It is a duplicate of a record that already exists, and the duplication is the point. The
 * before-run has to send the schema the old prompt was written against, because the schema IS prompt:
 * the {@code @JsonPropertyDescription} texts travel into the request, and a before-run using today's
 * four-field schema would be measuring a hybrid that never shipped — old instructions, new fields —
 * and attributing the difference to the contract.
 *
 * <p>Which means the usual instinct is exactly wrong here. Do NOT refactor this to share anything
 * with {@link org.aura.aura.resolver.ResolverOutput}; do not widen it when that record widens. Its
 * value comes entirely from being unable to move. Same rule as
 * {@code resolver_system_prompt_v4_frozen.md} beside it, for the same reason.
 */
public record BeforeResolverOutput(

        @JsonPropertyDescription("The complete message to send to the customer: warm, direct, at most three short paragraphs, no internal notes, labels, or JSON")
        String reply,

        @JsonPropertyDescription("True when this ticket must go to a human agent, judged by the <escalation> criteria in the system prompt; whenever this is true the reply itself must say so plainly")
        boolean escalate
) {}
