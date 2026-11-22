package io.github.org4j.observability.logging;

import io.github.org4j.masking.MaskingConstants;
import io.github.org4j.masking.MaskingRules;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the service masks in every log line, under {@code org4j.observability.logging.masking}.
 * Nothing is masked unless it is listed here.
 *
 * @param maskChar the character that replaces hidden ones; {@code .} when unset
 * @param fields names (case-insensitive) whose values keep their first 6 and last 4 characters when
 *     10 or longer, and are masked whole when shorter
 * @param fullFields names (case-insensitive) whose values are masked whole, at most 10 mask
 *     characters long; a name in both lists is masked whole
 * @param values regular expressions for values found by their form in any text, with or without a
 *     name; each match is masked like a name in {@code fields}. Write them as a YAML list or
 *     indexed properties: a comma-separated string splits a {@code {13,19}}.
 */
@ConfigurationProperties("org4j.observability.logging.masking")
public record LoggingMaskingProperties(
    String maskChar, List<String> fields, List<String> fullFields, List<String> values) {

  public LoggingMaskingProperties(
      @Nullable String maskChar,
      @Nullable List<String> fields,
      @Nullable List<String> fullFields,
      @Nullable List<String> values) {
    this.maskChar = Objects.requireNonNullElse(maskChar, MaskingConstants.DEFAULT_MASK_CHAR);
    this.fields = Objects.requireNonNullElse(fields, List.of());
    this.fullFields = Objects.requireNonNullElse(fullFields, List.of());
    this.values = Objects.requireNonNullElse(values, List.of());
  }

  /** The masking rules these properties describe. */
  public MaskingRules rules() {
    return new MaskingRules(
        maskChar,
        Set.copyOf(fields),
        Set.copyOf(fullFields),
        values.stream().map(Pattern::compile).toList());
  }
}
