package io.github.org4j.observability.context;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.context.integration.Slf4jThreadLocalAccessor;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the caller's context (the trace and the logging MDC) onto the thread that runs a task,
 * and clears it afterwards. The bean decorates Spring's executors; the static {@code wrap} methods
 * are for tasks handed to an executor Spring does not manage.
 */
public class ContextPropagatingTaskDecorator implements TaskDecorator {

  private final ContextSnapshotFactory snapshots;

  public ContextPropagatingTaskDecorator() {
    this(snapshots());
  }

  public ContextPropagatingTaskDecorator(ContextSnapshotFactory snapshots) {
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots must not be null");
  }

  @Override
  public Runnable decorate(Runnable runnable) {
    return snapshots.captureAll().wrap(runnable);
  }

  public static Runnable wrap(Runnable runnable) {
    return snapshots().captureAll().wrap(runnable);
  }

  public static <T> Callable<T> wrap(Callable<T> callable) {
    return snapshots().captureAll().wrap(callable);
  }

  public static Executor wrap(Executor executor) {
    return snapshots().captureAll().wrapExecutor(executor);
  }

  private static ContextSnapshotFactory snapshots() {
    registerMdc(ContextRegistry.getInstance());
    return ContextSnapshotFactory.builder().build();
  }

  private static synchronized void registerMdc(ContextRegistry registry) {
    var registered =
        registry.getThreadLocalAccessors().stream()
            .anyMatch(accessor -> Slf4jThreadLocalAccessor.KEY.equals(accessor.key()));
    if (!registered) {
      registry.registerThreadLocalAccessor(new Slf4jThreadLocalAccessor());
    }
  }
}
