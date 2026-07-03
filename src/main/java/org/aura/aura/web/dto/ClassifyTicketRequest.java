package org.aura.aura.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Inbound contract for /classify. Deliberately its own type rather than reusing
// ResolveTicketRequest: the two endpoints' contracts may drift apart (resolve will grow
// conversation context; classify won't), and sharing a DTO would weld them together.
public record ClassifyTicketRequest(

        // Same guards as the resolve DTO, same reasoning: reject blank input BEFORE the
        // paid model call, and cap length as the outermost token-cost fuse.
        @NotBlank(message = "message must not be blank")
        @Size(max = 4000, message = "message must be at most 4000 characters")
        String message
) {}
