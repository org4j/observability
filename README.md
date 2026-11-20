# observability

Logs, traces and metrics the same way in every service: logs with their trace on the console
(readable text, or ECS JSON with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) and over OTLP, one trace
per request carried to every call it makes, metrics over OTLP, every response naming its trace in
`X-Request-Id` (a 401 or 403 too), and every outgoing call logged. Every log line, on every sink,
is masked (card numbers, `key=value` secrets): messages and stack traces are masked; exception
attributes are not exported to OTLP (the masked trace travels in the message). Lines logged before
the context starts (the banner, the auto-configuration report, Vault's bootstrap) are not masked.

```groovy
implementation libs.observability
```

Adding the jar is enough: its `observability-defaults.yaml` loads itself below the service's own
configuration (OTLP endpoints from `OTLP_TRACES_ENDPOINT`, `OTLP_LOGS_ENDPOINT`,
`OTLP_METRICS_ENDPOINT`; sampling from `TRACING_SAMPLING`; ECS names for the trace; p95 histograms
for requests and pool waits). Those four variables are required, with no default: a service
without them doesn't start (config, "Required settings").

## Package Architecture

The module is structured into cohesive, domain-specific packages:

- `io.github.org4j.observability.http`: Inbound and outbound HTTP wire loggers (`HttpServerLoggingFilter`, `HttpClientLogging`), trace id propagation (`RequestIdFilter`), and HTTP properties (`HttpLoggingProperties`).
- `io.github.org4j.observability.logging`: Logback pattern converters (`LogMasking`), OpenTelemetry OTLP bridge (`MaskingOpenTelemetryAppender`), and masking properties (`LoggingMaskingProperties`).
- `io.github.org4j.observability.health`: Actuator health metric gauges (`HealthMetrics`).
- `io.github.org4j.observability`: Autoconfiguration entry point and shared constants.

## Properties

All configuration properties follow **fail-fast semantics** with zero hardcoded defaults.

### Logging & Masking (`org4j.observability.logging.masking`)

Masking operates with **zero hardcoded defaults**: nothing is masked unless the service lists it.
Three lists, all the service's own (the `masking` library):

- `fields`: names whose values keep their first 6 and last 4 characters when 10 or longer
  (`860030......7890`), and are masked whole when shorter (`...`);
- `full-fields`: names whose values are masked whole, at most 10 mask characters long; a name in both lists is masked whole;
- `values`: regular expressions for values found by their form in any text (a card number in a
  message), each match masked like a name in `fields`. Write them as a YAML list or indexed
  properties (`values[0]=…`): a comma-separated string splits a `{13,19}`.

Configured via `application.yaml`:

```yaml
org4j:
  observability:
    logging:
      masking:
        mask-char: "."            # masking character
        fields:                   # first 6 and last 4 kept (empty by default)
          - pan
          - card
        full-fields:              # masked whole (empty by default)
          - cvv
          - secret
          - access_token
        values:                   # found by their form in any text (empty by default)
          - '(?<!\d)\d{13,19}(?!\d)'
```

### HTTP Wire Logging (`org4j.observability.logging.http`)

Controls wire-level logging for inbound servlet requests and outbound client calls. When enabled, incoming and outgoing HTTP exchanges are captured, headers filtered/masked, and payloads sanitized:

```yaml
org4j:
  observability:
    logging:
      http:
        enabled: true             # master switch for HTTP wire logging
        max-body-bytes: 4000      # maximum payload bytes to log before capping
        include-headers:          # whitelisted header names to log (case-insensitive)
          - content-type
          - x-request-id
          - authorization
        exclude-paths:            # ant-style paths completely silenced (no log noise)
          - /actuator/**
          - /swagger-ui/**
          - /v3/api-docs/**
          - /favicon.ico
        no-body-paths:            # ant-style paths where payloads are logged as <not logged>
          - /v1/hsm/**
          - /v1/crypto/**
```

#### Configuring via HashiCorp Vault / Environment Variables for Ops

Ops teams can configure or update sensitive field lists and HTTP wire logging dynamically without code changes using HashiCorp Vault KV or environment variables:

**HashiCorp Vault KV**:
```json
{
  "org4j.observability.logging.masking.fields": "pan,card,cvv,secret,access_token",
  "org4j.observability.logging.masking.mask-char": ".",
  "org4j.observability.logging.http.enabled": "true",
  "org4j.observability.logging.http.max-body-bytes": "4000",
  "org4j.observability.logging.http.include-headers": "content-type,x-request-id,authorization",
  "org4j.observability.logging.http.exclude-paths": "/actuator/**,/swagger-ui/**"
}
```

**Environment Variables**:
```bash
PLATFORM_LOGGING_MASKING_FIELDS=pan,card,cvv,secret,access_token
PLATFORM_LOGGING_MASKING_MASK_CHAR=.
PLATFORM_LOGGING_HTTP_ENABLED=true
PLATFORM_LOGGING_HTTP_MAX_BODY_BYTES=4000
PLATFORM_LOGGING_HTTP_INCLUDE_HEADERS=content-type,x-request-id,authorization
PLATFORM_LOGGING_HTTP_EXCLUDE_PATHS=/actuator/**,/swagger-ui/**
```

| Property | What |
|---|---|
| `org4j.observability.logging.masking.mask-char` | Masking character used for obscured values |
| `org4j.observability.logging.masking.fields` | Field names whose values keep their first 6 and last 4 characters, across JSON, XML, forms, URIs, and text |
| `org4j.observability.logging.masking.full-fields` | Field names whose values are masked whole, at most 10 mask characters long |
| `org4j.observability.logging.masking.values` | Regular expressions for values masked wherever they appear, with or without a field name |
| `org4j.observability.logging.http.enabled` | Enables wire logging for inbound and outbound HTTP requests |
| `org4j.observability.logging.http.max-body-bytes` | Caps logged body payloads, truncating with `<more than N bytes>` |
| `org4j.observability.logging.http.include-headers` | Whitelist of HTTP headers to log; headers matching masking fields are masked by their field's rule |
| `org4j.observability.logging.http.exclude-paths` | URL path patterns completely excluded from HTTP wire logging |
| `org4j.observability.logging.http.no-body-paths` | URL path patterns where bodies are replaced with `<not logged>` |

## Client calls

`HttpClientLogging` logs every call of every client built from Boot's `RestClient.Builder` in at
most three lines (`→`, `←` with status and time, `✗`), with the URI and bodies masked and no
headers. Only a JSON, XML or form answer of up to 4 000 bytes is read for the log; anything else
streams through, logged by its size. A call that sends or receives a secret sets the request
attribute `HttpClientLogging.NO_BODIES` and is logged without either body (Keycloak's
`/client-secret` calls do). A client built on its own request factory (a SOAP one) adds the
`HttpClientLogging` bean itself.

## Health as a metric

With the actuator, every health indicator is the gauge `org4j.observability.health.up{component}` (1 UP, 0
otherwise), so an alert can see a component go DOWN (the local stack's "Health DOWN").

## Beans you can replace

`Masking` (the log masking), `HttpClientLogging`, `RequestIdFilter`.

## Tests

- `./gradlew test`: masked console and ECS lines, extra names, masked
  stack traces (console, ECS, OTLP), masked URIs, bounded client-log buffering.
- `./gradlew integrationTest`: Grafana's LGTM stack: one request's trace
  in Tempo and its masked log lines in Loki under the same trace id as `X-Request-Id`.
