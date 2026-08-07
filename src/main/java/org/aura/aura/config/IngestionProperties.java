package org.aura.aura.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The ingestion pipeline's operating switches, bound from {@code aura.ingest.*}.
 *
 * <p>Three fields, and each one is a safety property rather than a tuning knob — which is why they
 * are here, validated, instead of being {@code @Value} strings scattered across a constructor.
 *
 * @param enabled whether the pipeline runs at all. OFF by default. This is the same
 *                "absent means off" mechanism {@code CanaryProperties.enabled} uses, and it does the
 *                same double duty: it stops the ApplicationRunner from firing, AND it keeps the
 *                pipeline bean — with its two repository dependencies — from being constructed in
 *                the many test contexts that have no database at all
 * @param dir     the corpus directory, resolved against the working directory. A {@code String}
 *                rather than a {@code Path} for the reason the old loader documented: Spring's
 *                PathEditor resolves the value as a Resource first, so a colon reads as a URL scheme
 *                and a bare name can come back absolutised — more behaviour than "the directory the
 *                corpus is in" needs, and the extra behaviour is the part that surprises someone
 *                at 2am
 * @param force   override for the destructive-plan guards. Not a "do it harder" flag: it is the
 *                documented escape hatch for the one legitimate case the guards cannot distinguish
 *                from a mistake — deliberately deleting most of the corpus. It should never appear
 *                in a committed configuration file, only on a command line, which is why the default
 *                is false and the pipeline logs at WARN every time it is honoured
 */
@Validated
@ConfigurationProperties(prefix = "aura.ingest")
public record IngestionProperties(

        boolean enabled,

        @NotBlank(message = "aura.ingest.dir must name the corpus directory (it is resolved against "
                + "the working directory, so a relative name like `kb` is the normal value)")
        String dir,

        boolean force
) {

    public IngestionProperties {
        // Absent means the conventional location, so a fresh clone ingests with no configuration at
        // all. @NotBlank above then only ever fires on an explicitly blank value, which is a typo
        // rather than an omission — and the two deserve different treatment.
        if (dir == null || dir.isBlank()) dir = "kb";
    }
}
