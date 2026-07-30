package org.aura.aura.client;

/**
 * Voyage's {@code input_type} parameter, which tells the model whether it is embedding something to be
 * SEARCHED or something to search WITH.
 *
 * <p>It is not cosmetic. The same sentence embedded as a document and as a query lands in different
 * places, because Voyage prepends a different internal instruction in each case; a corpus embedded
 * with the wrong type retrieves measurably worse while looking completely healthy. Omitting the
 * parameter is worse still — it silently selects a third, un-instructed behaviour.
 *
 * <p>So this is a REQUIRED internal parameter, not an optional one, and it is deliberately kept off
 * the public surface of {@link VoyageEmbeddingClient}: callers choose a LANE (documents or a query)
 * and the client derives both the model and the input type from that choice. There is no method on
 * which a caller could pass the wrong pair.
 */
public enum EmbeddingInputType {

    QUERY("query"),
    DOCUMENT("document");

    private final String wireValue;

    EmbeddingInputType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The exact string Voyage expects — lower-case, and NOT derivable from {@code name()}. */
    public String wireValue() {
        return wireValue;
    }
}
