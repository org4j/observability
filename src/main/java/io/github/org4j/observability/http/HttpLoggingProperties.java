package io.github.org4j.observability.http;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.AntPathMatcher;

/**
 * HTTP wire logging, under {@code org4j.observability.logging.http}.
 *
 * @param enabled logs every request the service serves; client calls are logged either way
 * @param maxBodyBytes the longest body logged, in both directions; a longer one is logged as {@code
 *     <more than N bytes, type>}
 * @param includeHeaders headers logged with the request line (case-insensitive); none by default. A
 *     header named in the masking lists is masked by its rule.
 * @param excludePaths Ant-style paths whose requests are not logged at all
 * @param noBodyPaths Ant-style paths whose requests are logged without their bodies
 */
@ConfigurationProperties("org4j.observability.logging.http")
public record HttpLoggingProperties(
    boolean enabled,
    int maxBodyBytes,
    List<String> includeHeaders,
    List<String> excludePaths,
    List<String> noBodyPaths) {

  private static final AntPathMatcher PATHS = new AntPathMatcher();

  public HttpLoggingProperties(
      boolean enabled,
      @DefaultValue("4000") int maxBodyBytes,
      @Nullable List<String> includeHeaders,
      @Nullable List<String> excludePaths,
      @Nullable List<String> noBodyPaths) {
    this.enabled = enabled;
    this.maxBodyBytes = maxBodyBytes;
    this.includeHeaders = Objects.requireNonNullElse(includeHeaders, List.of());
    this.excludePaths = Objects.requireNonNullElse(excludePaths, List.of());
    this.noBodyPaths = Objects.requireNonNullElse(noBodyPaths, List.of());
  }

  /** Client logging when nothing is configured: no headers, bodies up to 4 000 bytes. */
  static HttpLoggingProperties defaults() {
    return new HttpLoggingProperties(false, 4_000, null, null, null);
  }

  public boolean isHeaderIncluded(String name) {
    return includeHeaders.stream().anyMatch(name::equalsIgnoreCase);
  }

  public boolean isExcluded(String path) {
    return matches(excludePaths, path);
  }

  public boolean isNoBody(String path) {
    return matches(noBodyPaths, path);
  }

  private static boolean matches(List<String> patterns, String path) {
    return patterns.stream().anyMatch(pattern -> PATHS.match(pattern, path));
  }
}
