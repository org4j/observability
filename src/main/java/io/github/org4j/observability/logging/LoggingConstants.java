package io.github.org4j.observability.logging;

import java.util.List;

final class LoggingConstants {

  // Logback's conversion words for the message and the trace, aliases included.
  static final List<String> MESSAGE_WORDS = List.of("m", "msg", "message");
  static final List<String> TRACE_WORDS =
      List.of("ex", "exception", "throwable", "xEx", "xException", "xThrowable", "wex", "wEx");

  // The ECS JSON members that carry free text.
  static final List<String> JSON_TEXT = List.of("message", "error.message", "error.stack_trace");

  static final String OTEL_APPENDER = "OTEL";

  private LoggingConstants() {}
}
