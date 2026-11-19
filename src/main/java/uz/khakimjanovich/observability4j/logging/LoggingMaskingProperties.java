package uz.khakimjanovich.observability4j.logging;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for automated log masking under observability4j.logging.masking.
 *
 * @param maskChar the character used for masking
 * @param fields list of sensitive field names to mask (case-insensitive)
 */
@ConfigurationProperties("observability4j.logging.masking")
public record LoggingMaskingProperties(@Nullable String maskChar, @Nullable List<String> fields) {}
