package io.github.org4j.observability.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import io.github.org4j.masking.Masking;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

// A trace with every message masked, causes and suppressed ones included, for the OTLP appender.
// It also carries a masked copy of the exception, of the same class where one can be made.
final class MaskedThrowableProxy extends ThrowableProxy {

  @Serial private static final long serialVersionUID = 1L;

  private final Throwable maskedThrowable;
  private final String className;
  private final @Nullable String maskedMessage;
  private final @Nullable IThrowableProxy maskedCause;
  private final IThrowableProxy[] maskedSuppressed;

  private MaskedThrowableProxy(
      Throwable maskedThrowable,
      String className,
      @Nullable String maskedMessage,
      @Nullable IThrowableProxy maskedCause,
      IThrowableProxy[] maskedSuppressed) {
    super(maskedThrowable);
    this.maskedThrowable = maskedThrowable;
    this.className = className;
    this.maskedMessage = maskedMessage;
    this.maskedCause = maskedCause;
    this.maskedSuppressed = maskedSuppressed;
  }

  static @Nullable MaskedThrowableProxy of(@Nullable IThrowableProxy proxy, Masking masking) {
    return of(proxy, masking, Collections.newSetFromMap(new IdentityHashMap<>()));
  }

  // A proxy already seen (a cycle of causes) is dropped.
  private static @Nullable MaskedThrowableProxy of(
      @Nullable IThrowableProxy proxy, Masking masking, Set<IThrowableProxy> seen) {
    if (proxy == null || !seen.add(proxy)) {
      return null;
    }
    var message = proxy.getMessage() == null ? null : masking.text(proxy.getMessage());
    var cause = of(proxy.getCause(), masking, seen);
    var suppressed =
        Arrays.stream(Objects.requireNonNullElse(proxy.getSuppressed(), new IThrowableProxy[0]))
            .map(each -> of(each, masking, seen))
            .filter(Objects::nonNull)
            .toArray(MaskedThrowableProxy[]::new);

    var throwable = copy(proxy, message, cause == null ? null : cause.getThrowable());
    for (var each : suppressed) {
      throwable.addSuppressed(each.getThrowable());
    }
    return new MaskedThrowableProxy(throwable, proxy.getClassName(), message, cause, suppressed);
  }

  private static Throwable copy(
      IThrowableProxy proxy, @Nullable String message, @Nullable Throwable cause) {
    if (proxy instanceof ThrowableProxy live && live.getThrowable() != null) {
      var copy = sameClass(live.getThrowable().getClass(), message, cause);
      copy.setStackTrace(live.getThrowable().getStackTrace());
      return copy;
    }
    var copy = new MaskedException(proxy.getClassName(), message, cause);
    var steps = proxy.getStackTraceElementProxyArray();
    if (steps != null) {
      copy.setStackTrace(
          Arrays.stream(steps)
              .map(StackTraceElementProxy::getStackTraceElement)
              .toArray(StackTraceElement[]::new));
    }
    return copy;
  }

  // Through a (String, Throwable) or a (String) constructor; without either, a MaskedException
  // that still prints the original class name.
  private static Throwable sameClass(
      Class<? extends Throwable> type, @Nullable String message, @Nullable Throwable cause) {
    var copy = construct(type, message, cause);
    return copy == null ? new MaskedException(type.getName(), message, cause) : copy;
  }

  private static @Nullable Throwable construct(
      Class<? extends Throwable> type, @Nullable String message, @Nullable Throwable cause) {
    try {
      var constructor = type.getDeclaredConstructor(String.class, Throwable.class);
      constructor.setAccessible(true);
      return constructor.newInstance(message, cause);
    } catch (ReflectiveOperationException | RuntimeException noCauseConstructor) {
      try {
        var constructor = type.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        var copy = constructor.newInstance(message);
        if (cause != null && copy.getCause() == null) {
          copy.initCause(cause);
        }
        return copy;
      } catch (ReflectiveOperationException | RuntimeException noMessageConstructor) {
        return null;
      }
    }
  }

  @Override
  public String getClassName() {
    return className;
  }

  @Override
  public @Nullable String getMessage() {
    return maskedMessage;
  }

  @Override
  public Throwable getThrowable() {
    return maskedThrowable;
  }

  @Override
  public @Nullable IThrowableProxy getCause() {
    return maskedCause;
  }

  @Override
  public IThrowableProxy[] getSuppressed() {
    return maskedSuppressed;
  }

  // Prints the original class name, so the trace reads as the real exception's.
  @SuppressWarnings("OverrideThrowableToString")
  static final class MaskedException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String className;

    MaskedException(String className, @Nullable String message, @Nullable Throwable cause) {
      super(message, cause);
      this.className = className;
    }

    @Override
    public String toString() {
      var message = getLocalizedMessage();
      return message == null ? className : className + ": " + message;
    }
  }
}
