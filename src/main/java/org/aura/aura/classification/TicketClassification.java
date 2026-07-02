package org.aura.aura.classification;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

// This record IS the output contract with the model. The SDK's outputConfig(Class<T>)
// derives a JSON schema from it and the API enforces that schema server-side (native
// structured outputs), so deserialization here can never meet a shape surprise — only
// a semantic one (see the confidence handling in TicketClassificationService).
//
// The @JsonPropertyDescription texts travel INTO the schema the model sees. They are
// prompt engineering, not documentation: each one steers what the model puts in the field.
public record TicketClassification(

        @JsonPropertyDescription("The single best-fitting topic of the ticket; use OTHER only when no listed category applies")
        TicketCategory category,

        @JsonPropertyDescription("Business urgency judged from impact and tone, not message length; CRITICAL only for blocked money or account compromise")
        TicketUrgency urgency,

        @JsonPropertyDescription("What the customer wants to happen next, independent of the topic")
        TicketIntent intent,

        @JsonPropertyDescription("Calibrated certainty in this classification from 0.0 to 1.0; be honest — a low score routes the ticket to a human, which is cheaper than a wrong guess")
        double confidence
) {}
