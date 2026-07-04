package org.aura.aura.streaming;

// One incremental chunk of the resolution answer, carried by a "delta" SSE frame.
// Wrapped in an object (not sent as a bare string) so the payload is JSON like every other
// frame — a client parses all frames the same way, and we can add fields (e.g. block index)
// later without breaking the "it's always JSON" assumption.
public record DeltaEvent(String text) {}
