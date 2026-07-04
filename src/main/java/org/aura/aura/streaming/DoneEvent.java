package org.aura.aura.streaming;

// Terminal success frame. Everything a client needs to finalize the turn once the text stops:
//  - stopReason: the model's own reason for stopping. "end_turn" is a complete answer;
//    "max_tokens" is a TRUNCATED-but-valid answer, surfaced here as data, NOT as an error —
//    the client decides whether a cut-off reply is acceptable.
//  - input/outputTokens: authoritative billing/observability figures, read straight off the
//    stream's usage frames (message_start carries input, message_delta carries output).
//  - elapsedMs: server-side wall-clock for the whole pump, a latency signal for dashboards.
public record DoneEvent(
        String stopReason,
        long inputTokens,
        long outputTokens,
        long elapsedMs
) {}
