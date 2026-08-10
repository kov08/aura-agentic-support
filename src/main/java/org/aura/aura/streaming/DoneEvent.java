package org.aura.aura.streaming;

// Terminal success frame. Everything a client needs to finalize the turn once the text stops:
//  - stopReason: the model's own reason for stopping. "end_turn" is a complete answer;
//    "max_tokens" is a TRUNCATED-but-valid answer, surfaced here as data, NOT as an error —
//    the client decides whether a cut-off reply is acceptable.
//  - input/outputTokens: authoritative billing/observability figures, read straight off the
//    stream's usage frames (message_start carries input, message_delta carries output).
//  - elapsedMs: server-side wall-clock for the whole pump, a latency signal for dashboards.
//  - outcome: Day 16. The ResolutionStatus name — RESOLVED or ESCALATED_TO_HUMAN — matching the
//    blocking endpoint's field of the same name, so one client vocabulary covers both transports.
//
// `outcome` is ADDITIVE: every field above keeps its name, type and meaning, and no event name
// changed, so an existing client that ignores unknown JSON properties is unaffected. It is added
// rather than deferred because Day 16 buffers this path behind the grounding gates — a stream that
// could not say whether it was delivering a grounded answer or an escalation would leave the gates
// invisible to the only client that reads it, and "the reply text says 'escalated'" is prose-sniffing
// of exactly the kind the Day 10 structured-output migration existed to end.
//
// Deliberately NOT here: the cited sources. The blocking endpoint publishes sourcesCited, and parity
// is a real gap — but DoneEvent is stop_reason + usage + timing, an OPERATIONAL frame, and hanging a
// grounding receipt off it would give one record two jobs. That wants its own frame and its own
// decision, not a field smuggled in beside the token counts.
public record DoneEvent(
        String stopReason,
        long inputTokens,
        long outputTokens,
        long elapsedMs,
        String outcome
) {}
