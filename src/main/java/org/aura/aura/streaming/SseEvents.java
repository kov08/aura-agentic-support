package org.aura.aura.streaming;

// The SSE "event:" names are a wire contract, not free text: the browser's EventSource
// dispatches on them (source.addEventListener("delta", ...)), so a typo here silently
// breaks a client listener with no compiler to catch it. Centralizing them as constants
// makes the whole contract greppable and impossible to misspell at a call site.
final class SseEvents {

    // First frame: the Day 6 classification verdict, so a client can route/label the ticket
    // before a single token of the answer arrives.
    static final String CLASSIFICATION = "classification";

    // Repeated frame: one incremental chunk of the resolution text.
    static final String DELTA = "delta";

    // Terminal success frame: stop_reason + usage + timing. Its arrival means "stream finished
    // cleanly" — the absence of it (socket just closes) is how a client detects a drop.
    static final String DONE = "done";

    // Terminal failure frame: an RFC 9457 problem, same shape as the Day 5 REST errors.
    static final String ERROR = "error";

    private SseEvents() {
        // Constants holder — never instantiated.
    }
}
