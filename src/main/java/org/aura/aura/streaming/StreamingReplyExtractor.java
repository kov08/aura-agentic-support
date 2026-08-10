package org.aura.aura.streaming;

/**
 * Unwraps the customer-facing {@code reply} text out of a structured-output JSON envelope, one
 * arbitrary chunk at a time.
 *
 * <p>Day 10 made the resolver's output schema-enforced (see
 * {@link org.aura.aura.resolver.ResolverOutput}), which means the SSE transport now receives JSON on
 * the wire instead of prose. Forwarding those deltas straight to the browser as we did on Day 7 would
 * paint {@code &#123;"reply":"I'm sorry it's tak} across the customer's screen. This class stands
 * between the two: feed it raw text deltas, forward exactly what it hands back.
 *
 * <p><b>Pure and stateful.</b> No Spring, no I/O, one instance per stream, not thread-safe — the pump
 * owns one and drives it from a single thread. That is what makes the whole thing unit-testable
 * without a network, which is the point.
 *
 * <h2>Day 16: PARKED, not dead — and the difference is a decision, not a hope</h2>
 * Nothing in production calls this today. Decision 4 buffers the SSE reply behind the grounding
 * gates, and a buffered reply needs no incremental unescaping: {@code objectMapper.readValue} hands
 * back the whole string correctly decoded. So the pump stopped feeding it.
 *
 * <p>It is kept because the reason it stopped being called is temporary and named. Phase 4's routing
 * restores genuine streaming on the paths that owe no citations — the tickets the wide-denominator
 * over-refusal number identified — and on those paths the model's output is still a JSON envelope,
 * so the character-by-character problem this class solves comes back unchanged. Deleting it would
 * mean rewriting a state machine whose hard cases (a split {@code \\uXXXX}, an escaped quote that
 * must not terminate the string) were paid for once already and are still covered by 16 tests.
 *
 * <p>The honest risk of parking rather than deleting is that it rots — a schema change moves
 * {@code reply} out of first position and nothing fails, because nothing calls this. That is
 * precisely why {@link org.aura.aura.resolver.ResolverOutput} states the field-order constraint as a
 * standing rule rather than as a description of current behaviour: the constraint is dormant, not
 * repealed. If Phase 4 does not land, delete this class rather than leaving it to age.
 *
 * <h2>What it emits</h2>
 * Only the contents of the top-level {@code "reply"} string, UNESCAPED — the customer sees a real
 * newline, never a literal backslash-n. Everything else is suppressed: leading whitespace, the
 * opening brace, the key itself, the quotes that delimit it, the comma, and the entire
 * {@code escalate} field that follows.
 *
 * <h2>Chunk-split tolerance</h2>
 * Every piece of state below exists because a chunk boundary can fall anywhere. A delta may arrive
 * mid-key ({@code "re} + {@code ply"}), mid-escape ({@code \} + {@code n}), mid-unicode-escape
 * ({@code \\u00} + {@code e9}), or be empty. Nothing here assumes a chunk is a token, a line, or
 * even a complete character.
 *
 * <p>(The doubled backslashes throughout this comment are an artefact of the Java lexer, which
 * resolves unicode escapes in translation phase 1 — BEFORE comments are stripped. A bare
 * backslash-u in a javadoc is a compile error, not documentation.)
 *
 * <h2>JSON string escapes — the whole job</h2>
 * Inside the reply string the parser handles, per RFC 8259:
 * <ul>
 *   <li>{@code \"} → a quote that does NOT end the string — the single most important case, since
 *       treating it as a terminator would truncate any reply containing quoted text;</li>
 *   <li>{@code \\} → one backslash, and critically it consumes the escape so a trailing
 *       {@code ...\\"} still terminates correctly;</li>
 *   <li>{@code \n \t \r \b \f} → the real control characters;</li>
 *   <li>{@code \/} → a plain slash (legal, rarely emitted);</li>
 *   <li>{@code \\uXXXX} → the code unit, with the four hex digits accumulated across any split.
 *       Surrogate pairs need no special case: a high and low surrogate appended in sequence form the
 *       correct character in a UTF-16 Java string on their own.</li>
 * </ul>
 * An unrecognised escape emits the character literally rather than throwing — the server enforces the
 * schema, so malformed JSON here would mean an SDK or transport bug, and a garbled character on
 * screen beats killing a customer's stream over it.
 *
 * <h2>The one coupling</h2>
 * This relies on {@code reply} being the FIRST property of the schema, which
 * {@link org.aura.aura.resolver.ResolverOutput} documents and pins. Because nothing precedes it, the
 * first {@code "reply"} token in the document is always the real key and can never be a substring of
 * some earlier field's value — which is what lets this be a small state machine instead of a JSON
 * parser. Move {@code reply} out of first position and that assumption silently breaks.
 */
public final class StreamingReplyExtractor {

    /** The key token, quotes included, matched incrementally across chunk boundaries. */
    private static final String REPLY_KEY = "\"reply\"";

    private enum State {
        /** Scanning for the {@code "reply"} key token. */
        BEFORE_KEY,
        /** Key matched; skipping whitespace until the colon. */
        AFTER_KEY,
        /** Colon seen; skipping whitespace until the value's opening quote. */
        AWAIT_OPEN_QUOTE,
        /** Inside the reply string — this is the only state that emits. */
        IN_STRING,
        /** A backslash was seen; the next character selects the escape. */
        ESCAPE,
        /** Inside {@code \\uXXXX}; accumulating up to four hex digits. */
        UNICODE,
        /** Closing quote seen. The reply is complete; everything after is suppressed forever. */
        DONE
    }

    private State state = State.BEFORE_KEY;
    private int keyMatched = 0;
    private int unicodeDigits = 0;
    private int unicodeValue = 0;

    /**
     * Feed one raw text delta; returns exactly the characters to forward to the customer, which is
     * very often the empty string (while the envelope's scaffolding streams past).
     */
    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty() || state == State.DONE) return "";
        StringBuilder out = new StringBuilder(chunk.length());
        for (int i = 0; i < chunk.length(); i++) {
            accept(chunk.charAt(i), out);
        }
        return out.toString();
    }

    /** True once the reply string has been fully delivered. */
    public boolean isComplete() {
        return state == State.DONE;
    }

    private void accept(char c, StringBuilder out) {
        switch (state) {
            case BEFORE_KEY -> {
                if (c == REPLY_KEY.charAt(keyMatched)) {
                    if (++keyMatched == REPLY_KEY.length()) {
                        state = State.AFTER_KEY;
                        keyMatched = 0;
                    }
                } else {
                    // Restart the match, but re-test THIS character against position 0 rather than
                    // discarding it. Without that, the closing quote of a preceding token followed
                    // immediately by the key would swallow the character that begins the real match.
                    keyMatched = (c == REPLY_KEY.charAt(0)) ? 1 : 0;
                }
            }
            case AFTER_KEY -> {
                if (c == ':') state = State.AWAIT_OPEN_QUOTE;
                // anything else here is insignificant whitespace — skip it
            }
            case AWAIT_OPEN_QUOTE -> {
                if (c == '"') state = State.IN_STRING;
            }
            case IN_STRING -> {
                if (c == '\\') state = State.ESCAPE;
                else if (c == '"') state = State.DONE;   // unescaped quote = end of the reply
                else out.append(c);
            }
            case ESCAPE -> {
                switch (c) {
                    case 'n' -> append('\n', out);
                    case 't' -> append('\t', out);
                    case 'r' -> append('\r', out);
                    case 'b' -> append('\b', out);
                    case 'f' -> append('\f', out);
                    case '"' -> append('"', out);
                    case '\\' -> append('\\', out);
                    case '/' -> append('/', out);
                    case 'u' -> {
                        state = State.UNICODE;
                        unicodeDigits = 0;
                        unicodeValue = 0;
                    }
                    // Unrecognised escape: emit it literally rather than throw (see class javadoc).
                    default -> append(c, out);
                }
            }
            case UNICODE -> {
                int digit = Character.digit(c, 16);
                // A non-hex digit is impossible in server-enforced JSON; treat it as 0 rather than
                // corrupting the state machine, and let the end-of-stream parse be the real detector.
                unicodeValue = (unicodeValue << 4) + Math.max(digit, 0);
                if (++unicodeDigits == 4) {
                    // Cast is safe and surrogate-correct: a 😀 pair arrives as two
                    // consecutive code units and appending them in order rebuilds the character.
                    out.append((char) unicodeValue);
                    state = State.IN_STRING;
                }
            }
            case DONE -> { /* the reply is closed; suppress the rest of the envelope */ }
        }
    }

    private void append(char c, StringBuilder out) {
        out.append(c);
        state = State.IN_STRING;
    }
}
