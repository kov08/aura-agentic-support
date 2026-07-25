package org.aura.aura;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The Anthropic transport configuration, bound from {@code aura.anthropic.*} and validated at startup.
 *
 * <p><b>Fail-fast on a missing key.</b> {@code apiKey} is {@code @NotBlank}, so a blank or absent key
 * fails the context at binding time with a clear message — instead of the app booting, appearing healthy,
 * and only 401-ing on the first Claude call (the confusing failure mode this replaces). The property
 * binds from the {@code ANTHROPIC_API_KEY} environment variable via a placeholder in application.yml, so
 * the operator-facing env-var name is unchanged; the {@code @NotBlank} message names it explicitly so the
 * startup error points straight at what to set (Spring's default binding error would only name the
 * property path {@code aura.anthropic.apiKey}).
 *
 * <p>{@code baseUrl} and {@code timeout} are the Day 11 seam, moved here from ad-hoc {@code @Value}s so
 * the whole transport config is one validated object. {@code baseUrl} empty means the SDK's default
 * endpoint; the compact constructor defaults the two optionals so a context that omits them (a slice
 * test) still binds — only {@code apiKey} is mandatory.
 */
@Validated
@ConfigurationProperties(prefix = "aura.anthropic")
public record AnthropicProperties(

        @NotBlank(message = "ANTHROPIC_API_KEY must be set (it binds to aura.anthropic.api-key) — "
                + "AURA cannot call Claude without an API key")
        String apiKey,

        String baseUrl,
        Duration timeout
) {
    public AnthropicProperties {
        if (baseUrl == null) baseUrl = "";                 // empty -> SDK default endpoint
        if (timeout == null) timeout = Duration.ofSeconds(30);
    }
}
