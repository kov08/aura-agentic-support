package org.aura.aura.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Inbound contract. A DTO, not a domain type: defines exactly what input is legal.
public record ResolveTicketRequest(

        // @NotBlank: present and not just whitespace -> rejects empty input
        // BEFORE any paid Claude call is made.
        @NotBlank(message = "message must not be blank")

        // @Size(max=4000): cheapest, outermost guard on token cost.
        // A hard cap on input LENGTH (not content). Real budgeting comes in Phase 4.
        @Size(max = 4000, message = "message must be at most 4000 characters")
        String message
) {}
