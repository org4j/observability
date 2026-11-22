package io.github.org4j.observability.health;

import static io.github.org4j.observability.ObservabilityConstants.COMPONENT_TAG;
import static io.github.org4j.observability.ObservabilityConstants.HEALTH_METRIC;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;

/**
 * Publishes each health indicator as the gauge {@value
 * io.github.org4j.observability.ObservabilityConstants#HEALTH_METRIC}{@code {component}}: 1 while
 * UP, 0 otherwise or when the check throws, so an alert can see a component go down.
 */
@RequiredArgsConstructor
public class HealthMetrics implements SmartInitializingSingleton {

  private final ObjectProvider<HealthContributorRegistry> contributors;
  private final ObjectProvider<MeterRegistry> meters;

  @Override
  public void afterSingletonsInstantiated() {
    var registry = contributors.getIfAvailable();
    var meterRegistry = meters.getIfAvailable();
    if (registry == null || meterRegistry == null) {
      return;
    }
    registry.stream()
        .filter(named -> named.contributor() instanceof HealthIndicator)
        .forEach(
            named ->
                Gauge.builder(
                        HEALTH_METRIC, (HealthIndicator) named.contributor(), HealthMetrics::up)
                    .description("1 while the component's health is UP")
                    .tag(COMPONENT_TAG, named.name())
                    .register(meterRegistry));
  }

  private static double up(HealthIndicator indicator) {
    try {
      var health = indicator.health();
      return health != null && Status.UP.equals(health.getStatus()) ? 1 : 0;
    } catch (RuntimeException e) {
      return 0;
    }
  }
}
