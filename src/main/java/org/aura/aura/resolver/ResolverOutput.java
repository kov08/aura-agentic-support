package org.aura.aura.resolver;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.Objects;

/**
 * The resolver's output contract with the model — the Day 6 classifier pattern applied to the
 * resolve path. {@code outputConfig(Class<T>)} derives a JSON schema from this record and the API
 * enforces it server-side, so the customer reply and the model's verdicts arrive as ONE atomic,
 * shape-guaranteed answer instead of prose we would have to infer intent from.
 *
 * <p><b>Only model JUDGMENTS belong here.</b> {@code sourcesProvided} is deliberately absent: it is a
 * deterministic retrieval receipt that {@link org.aura.aura.retrieval.ContextBlockAssembler} builds
 * from the surviving chunk set, and that {@link ResolverService} copies through untouched. Moving it
 * into this schema would demote it to a model self-report the model could fabricate — and the eval's
 * {@code expectedSources} check would then be grading a claim about retrieval rather than retrieval
 * itself, hiding the exact retrieval failure it exists to expose.
 *
 * <p>Day 14 made that separation matter more, not less. Under the Day 4 keyword knowledge base a
 * whiffed retrieval returned nothing and the empty receipt was obvious; semantic search always
 * returns a ranked "best" match, so the ledger is now the only thing that distinguishes a grounded
 * answer from a confident one. A ledger the model could write would distinguish nothing at all.
 *
 * <h2>Day 16: {@code citations} IS model-written, and that is not a contradiction</h2>
 * {@code citations} looks like the field the paragraph above forbids, and the difference is worth
 * stating precisely: it does not replace the ledger, it is the model's CLAIM about which entries of
 * the ledger it used, kept on its own channel so the two can be compared. That comparison is the
 * whole point — {@code ResolverService}'s G4 gate checks every id against the set actually put in
 * front of the model this request, and a claim that fails is a measurable model-misbehaviour signal
 * rather than a citation nobody can check. A self-reported field is only dangerous when nothing
 * verifies it; verified against a deterministic record, it becomes evidence.
 *
 * <h2>Component order is wire order, and Day 16 makes it load-bearing twice</h2>
 * Record component order = generated schema property order = the order the model streams its fields.
 * Two separate constraints pin this ordering, and they pull in opposite directions:
 *
 * <ul>
 *   <li><b>{@code reply} MUST stay FIRST</b> — the SSE path
 *       ({@link org.aura.aura.streaming.StreamingReplyExtractor}) forwards the reply's characters as
 *       they arrive, so any field generated ahead of it would delay the customer's first visible
 *       character by however long that field takes to emit. The extractor also relies on nothing
 *       preceding {@code reply} so the first {@code "reply"} token in the document is always the real
 *       key rather than a substring of an earlier field's value.</li>
 *   <li><b>{@code grounded} MUST stay LAST</b> (Decision 3 — verdict-last). Generation is
 *       autoregressive: a field is conditioned on every field before it. Asked FIRST, "are you
 *       grounded?" is a prediction the rest of the answer then has to live up to, and the model will
 *       write an answer that justifies the verdict it already committed to. Asked LAST it is a
 *       RETROSPECTIVE SELF-ASSESSMENT — the answer and the citations are already on the page, and the
 *       verdict judges them. Same field, same schema, completely different question.</li>
 * </ul>
 *
 * <p>{@code escalate} sits between them rather than after {@code grounded} for the same reason:
 * {@code grounded} is the final judgement over everything the model has already committed to,
 * including its own escalation decision.
 *
 * <p><b>The schema widens; it is never rewritten</b> (Decision 2). A future {@code claims[]} field —
 * per-sentence attribution rather than per-answer — is an ADDITION after {@code escalate}, not a
 * redefinition of {@code citations}. That constraint exists because every field here is also prompt:
 * the {@code @JsonPropertyDescription} texts travel into the schema the model reads, so redefining
 * one silently changes behaviour while the field name in the code stays put.
 */
public record ResolverOutput(

        @JsonPropertyDescription("The complete message to send to the customer: warm, direct, at most three short paragraphs, no internal notes, labels, or JSON. Leave this EMPTY when grounded is false")
        String reply,

        @JsonPropertyDescription("The id attribute of every <document> excerpt this reply actually drew a fact from, copied verbatim. Non-empty whenever grounded is true; empty when grounded is false. Never invent an id and never list an excerpt you did not use")
        List<String> citations,

        @JsonPropertyDescription("True when this ticket must go to a human agent, judged by the <escalation> criteria in the system prompt; whenever this is true the reply itself must say so plainly")
        boolean escalate,

        @JsonPropertyDescription("Judged LAST, about the reply you have just written: true only if every ShopFast-specific fact in it comes from a cited excerpt. False when the excerpts do not contain what the customer needs — which is a correct outcome, not a failure")
        boolean grounded
) {

    /**
     * Null-tolerant on {@code citations}, and defensively copied.
     *
     * <p>The schema makes the field required, so an absent array should be impossible — but "should
     * be impossible" is exactly the assumption that turns a provider-side hiccup into a
     * {@code NullPointerException} three layers away in the G4 gate. Absent and empty mean the same
     * thing to every reader downstream (no citations were offered), so they are normalised to one
     * representation here rather than being distinguished by every call site.
     *
     * <p>Null ELEMENTS are filtered rather than passed through, purely so the copy below cannot throw:
     * {@code List.copyOf} rejects nulls, and a null id inside the array would otherwise surface as an
     * NPE during deserialization instead of as the citation violation it actually is.
     */
    public ResolverOutput {
        citations = citations == null
                ? List.of()
                : citations.stream().filter(Objects::nonNull).toList();
    }
}
