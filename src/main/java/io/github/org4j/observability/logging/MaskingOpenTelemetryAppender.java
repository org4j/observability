package io.github.org4j.observability.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;

/** The OpenTelemetry appender, given each event with its message and trace masked. */
public class MaskingOpenTelemetryAppender extends OpenTelemetryAppender {

  @Override
  protected void append(ILoggingEvent event) {
    super.append(LogMasking.masked(event));
  }
}
