package io.github.org4j.observability.http;

import io.github.org4j.masking.Masking;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

// What the server filter and the client interceptor write the same way.
final class WireLog {

  private WireLog() {}

  // Masking can search only these by name; anything else is logged by its size.
  static boolean readable(@Nullable Object contentType) {
    if (contentType == null) {
      return false;
    }
    var type = contentType.toString().toLowerCase(Locale.ROOT);
    return type.contains("json") || type.contains("xml") || type.contains("x-www-form-urlencoded");
  }

  static String size(long length, @Nullable Object contentType) {
    return "<" + (length < 0 ? "?" : length) + " bytes" + typeSuffix(contentType) + ">";
  }

  static String tooLong(int max, @Nullable Object contentType) {
    return "<more than " + max + " bytes" + typeSuffix(contentType) + ">";
  }

  // [Name: value, ...] for the included headers, empty when none is included.
  static String headers(
      Masking masking,
      HttpLoggingProperties properties,
      Iterable<String> names,
      Function<String, List<String>> values) {
    var joined = new StringBuilder();
    for (var name : names) {
      if (!properties.isHeaderIncluded(name)) {
        continue;
      }
      for (var value : values.apply(name)) {
        if (!joined.isEmpty()) {
          joined.append(", ");
        }
        joined
            .append(name)
            .append(": ")
            .append(masking.contains(name) ? masking.mask(name, value) : value);
      }
    }
    return joined.isEmpty() ? "" : " [" + joined + "]";
  }

  static long millisSince(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  private static String typeSuffix(@Nullable Object contentType) {
    return contentType == null ? "" : ", " + contentType;
  }
}
