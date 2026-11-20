package io.github.org4j.observability.logging;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the service masks in every log line, under org4j.observability.logging.masking. Nothing is
 * masked unless it is listed here.
 *
 * @param maskChar the character used for masking
 * @param fields field names (case-insensitive) whose values keep their first 6 and last 4
 *     characters when 10 or longer, and are masked whole when shorter
 * @param fullFields field names (case-insensitive) whose values are masked whole, at most 10 mask
 *     characters long; a name listed in both lists is masked whole
 * @param values regular expressions for values found by their form in any text, with or without a
 *     field name; each match keeps its first 6 and last 4 characters like a name in fields. Write
 *     them as a YAML list or indexed properties: a comma-separated string splits a {13,19}.
 */
@ConfigurationProperties("org4j.observability.logging.masking")
public record LoggingMaskingProperties(
    @Nullable String maskChar,
    @Nullable List<String> fields,
    @Nullable List<String> fullFields,
    @Nullable List<String> values) {}
