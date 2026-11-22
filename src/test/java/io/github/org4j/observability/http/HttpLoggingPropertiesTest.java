package io.github.org4j.observability.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class HttpLoggingPropertiesTest {

  private static HttpLoggingProperties bind(Map<String, String> properties) {
    return new Binder(new MapConfigurationPropertySource(properties))
        .bindOrCreate("org4j.observability.logging.http", HttpLoggingProperties.class);
  }

  @Test
  void switchingItOnIsEnough() {
    var properties = bind(Map.of("org4j.observability.logging.http.enabled", "true"));

    assertThat(properties.enabled()).isTrue();
    assertThat(properties.maxBodyBytes()).isEqualTo(4000);
    assertThat(properties.includeHeaders()).isEmpty();
    assertThat(properties.isExcluded("/actuator/health")).isFalse();
    assertThat(properties.isNoBody("/payments")).isFalse();
  }

  @Test
  void matchesHeadersIgnoringCaseAndPathsByAntPattern() {
    var properties =
        bind(
            Map.of(
                "org4j.observability.logging.http.include-headers", "content-type",
                "org4j.observability.logging.http.exclude-paths", "/actuator/**",
                "org4j.observability.logging.http.no-body-paths", "/v1/hsm/**"));

    assertThat(properties.isHeaderIncluded("Content-Type")).isTrue();
    assertThat(properties.isHeaderIncluded("Authorization")).isFalse();
    assertThat(properties.isExcluded("/actuator/health/liveness")).isTrue();
    assertThat(properties.isNoBody("/v1/hsm/keys")).isTrue();
    assertThat(properties.isNoBody("/v1/payments")).isFalse();
  }
}
