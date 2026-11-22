package io.github.org4j.observability.logging;

import static io.github.org4j.observability.logging.LoggingConstants.JSON_TEXT;
import static io.github.org4j.observability.logging.LoggingConstants.MESSAGE_WORDS;
import static io.github.org4j.observability.logging.LoggingConstants.OTEL_APPENDER;
import static io.github.org4j.observability.logging.LoggingConstants.TRACE_WORDS;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import io.github.org4j.masking.Masking;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks every log line on every sink: the console pattern's message and trace, the ECS JSON message
 * and stack trace, and the events sent over OTLP.
 *
 * <p>Logback is configured before the application context, so the masking is a static the context
 * {@linkplain #install installs}; lines logged before that are not masked.
 */
public final class LogMasking {

  private static volatile Masking masking = Masking.defaults();

  private LogMasking() {}

  /**
   * Masks with {@code rules} from now on, and makes every pattern appender on the root logger mask
   * its message and trace conversion words ({@code %m}, {@code %ex} and their aliases).
   */
  public static void install(LoggerContext context, Masking rules) {
    masking = rules;
    for (var appenders = context.getLogger(Logger.ROOT_LOGGER_NAME).iteratorForAppenders();
        appenders.hasNext(); ) {
      if (appenders.next() instanceof OutputStreamAppender<?> stream
          && stream.getEncoder() instanceof LayoutWrappingEncoder<?> encoder
          && encoder.getLayout() instanceof PatternLayout layout) {
        var converters = layout.getInstanceConverterMap();
        MESSAGE_WORDS.forEach(word -> converters.put(word, Message::new));
        TRACE_WORDS.forEach(word -> converters.put(word, Trace::new));
        layout.stop();
        layout.start();
      }
    }
  }

  /** Sends every log line over OTLP, masked, through {@link MaskingOpenTelemetryAppender}. */
  public static void installOpenTelemetry(LoggerContext context, OpenTelemetry openTelemetry) {
    var appender = new MaskingOpenTelemetryAppender();
    appender.setContext(context);
    appender.setName(OTEL_APPENDER);
    appender.start();
    context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    OpenTelemetryAppender.install(openTelemetry);
  }

  /** {@code %m} with the message masked. */
  public static class Message extends MessageConverter {
    @Override
    public String convert(ILoggingEvent event) {
      return Objects.requireNonNullElse(masking.text(super.convert(event)), "");
    }
  }

  /** {@code %ex} with every message in the trace masked. */
  public static class Trace extends ExtendedWhitespaceThrowableProxyConverter {
    @Override
    protected String throwableProxyToString(IThrowableProxy proxy) {
      return Objects.requireNonNullElse(masking.text(super.throwableProxyToString(proxy)), "");
    }
  }

  /** Masks the message and the stack trace of ECS JSON lines ({@code logging.structured}). */
  public static class Json implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {
    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
      for (var path : JSON_TEXT) {
        // A lambda, not masking::text: install may replace the masking after this runs.
        members.applyingValueProcessor(
            JsonWriter.ValueProcessor.of(String.class, text -> masking.text(text))
                .whenHasPath(path));
      }
    }
  }

  // The event as OTLP sees it: masked message, no arguments to leak, masked trace.
  static ILoggingEvent masked(ILoggingEvent event) {
    var trace = MaskedThrowableProxy.of(event.getThrowableProxy(), masking);
    var message = masking.text(event.getFormattedMessage());
    return (ILoggingEvent)
        Proxy.newProxyInstance(
            ILoggingEvent.class.getClassLoader(),
            new Class<?>[] {ILoggingEvent.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getFormattedMessage", "getMessage" -> message;
                  case "getArgumentArray" -> null;
                  case "getThrowableProxy" -> trace;
                  default -> {
                    try {
                      yield method.invoke(event, arguments);
                    } catch (InvocationTargetException e) {
                      throw e.getCause();
                    }
                  }
                });
  }
}
