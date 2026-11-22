package io.github.org4j.observability;

import ch.qos.logback.classic.LoggerContext;
import io.github.org4j.breaker.Breaker;
import io.github.org4j.masking.Masking;
import io.github.org4j.observability.breaker.BreakerMetrics;
import io.github.org4j.observability.context.ContextPropagatingTaskDecorator;
import io.github.org4j.observability.health.HealthMetrics;
import io.github.org4j.observability.http.HttpClientLogging;
import io.github.org4j.observability.http.HttpLoggingProperties;
import io.github.org4j.observability.http.HttpServerLoggingFilter;
import io.github.org4j.observability.http.RequestIdFilter;
import io.github.org4j.observability.logging.LogMasking;
import io.github.org4j.observability.logging.LoggingMaskingProperties;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

/**
 * Wires the library into a Spring Boot service: masked logs on every sink, client and server wire
 * logging, {@code X-Request-Id}, health and breaker gauges, and context carried across threads.
 * Every bean backs off when the service defines its own.
 */
@AutoConfiguration
@EnableConfigurationProperties({LoggingMaskingProperties.class, HttpLoggingProperties.class})
public class ObservabilityAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Masking logMasking(LoggingMaskingProperties properties) {
    return new Masking(properties.rules());
  }

  @Bean
  InitializingBean maskedLogs(Masking logMasking, ObjectProvider<OpenTelemetry> openTelemetry) {
    return () -> {
      var context = (LoggerContext) LoggerFactory.getILoggerFactory();
      LogMasking.install(context, logMasking);
      openTelemetry.ifAvailable(otel -> LogMasking.installOpenTelemetry(context, otel));
    };
  }

  @Bean
  @ConditionalOnMissingBean
  HttpClientLogging httpClientLogging(Masking logMasking, HttpLoggingProperties properties) {
    return new HttpClientLogging(logMasking, properties);
  }

  @Bean
  RestClientCustomizer httpClientLoggingCustomizer(HttpClientLogging httpClientLogging) {
    return builder -> builder.requestInterceptor(httpClientLogging);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  static class WebLogging {

    @Bean
    @ConditionalOnProperty(name = "org4j.observability.logging.http.enabled", havingValue = "true")
    @ConditionalOnMissingBean
    HttpServerLoggingFilter httpServerLoggingFilter(
        Masking logMasking, HttpLoggingProperties httpLoggingProperties) {
      return new HttpServerLoggingFilter(logMasking, httpLoggingProperties);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  @ConditionalOnClass(Tracer.class)
  static class RequestIds {

    @Bean
    @ConditionalOnMissingBean
    RequestIdFilter requestIdFilter(ObjectProvider<Tracer> tracer) {
      return new RequestIdFilter(tracer);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(HealthContributorRegistry.class)
  static class Health {

    @Bean
    @ConditionalOnMissingBean
    HealthMetrics healthMetrics(
        ObjectProvider<HealthContributorRegistry> registry, ObjectProvider<MeterRegistry> meters) {
      return new HealthMetrics(registry, meters);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({Breaker.class, MeterRegistry.class})
  static class Breakers {

    @Bean
    @ConditionalOnMissingBean
    BreakerMetrics breakerMetrics(
        ObjectProvider<Breaker> breakers, ObjectProvider<MeterRegistry> meters) {
      return new BreakerMetrics(breakers, meters);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({TaskDecorator.class, ContextSnapshot.class})
  static class AsyncContext {

    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    TaskDecorator contextPropagatingTaskDecorator() {
      return new ContextPropagatingTaskDecorator();
    }
  }
}
