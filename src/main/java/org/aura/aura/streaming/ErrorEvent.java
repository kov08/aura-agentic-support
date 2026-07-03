package org.aura.aura.streaming;

// Terminal failure frame, shaped like RFC 9457 ProblemDetail (type/title/status/detail) — the
// SAME shape the Day 5 GlobalExceptionHandler returns for REST errors. A failure BEFORE the
// stream opens arrives as an application/problem+json body; a failure MID-stream arrives as
// this "error" SSE frame. Keeping one shape means a client writes one error parser, not two.
//
// Why a status field on a response that already committed HTTP 200: once the first SSE frame
// is flushed the status line is locked to 200, so the real "what went wrong" verdict has to
// travel in the body. 502 mirrors the Day 8 mapping (upstream Claude failure -> Bad Gateway).
public record ErrorEvent(
        String type,
        String title,
        int status,
        String detail
) {
    // One canonical upstream-failure event. detail is a fixed, safe string — NEVER the raw
    // exception message — so we don't leak internals onto the wire (same stance as the Day 5
    // handleGeneric handler); the real cause is logged server-side instead.
    static ErrorEvent upstreamFailure() {
        return new ErrorEvent(
                "about:blank",                 // RFC 9457 default when there's no type URI (matches Day 5)
                "Upstream error",
                502,                           // Bad Gateway: our dependency (Claude) failed, not the client
                "The assistant failed while generating a response. Please retry the request.");
    }
}
