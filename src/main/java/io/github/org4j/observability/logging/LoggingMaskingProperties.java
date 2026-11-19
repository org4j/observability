package io.github.org4j.observability.logging;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for automated log masking under org4j.observability.logging.masking.
 *
 * @param maskChar the character used for masking
 * @param fields list of sensitive field names to mask (case-insensitive)
 */
@ConfigurationProperties("org4j.observability.logging.masking")
public record LoggingMaskingProperties(@Nullable String maskChar, @Nullable List<String> fields) {}
