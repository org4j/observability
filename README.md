# observability

Logs, traces and metrics the same way in every service: logs with their trace on the console
(readable text, or ECS JSON with `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) and over OTLP, one trace
per request carried to every call it makes, metrics over OTLP, every response naming its trace in
`X-Request-Id` (a 401 or 403 too), and every outgoing call logged. Every log line, on every sink,
is masked: messages and stack traces on the console, in ECS JSON and over OTLP. Lines logged before
the context starts (the banner, the auto-configuration report, Vault's bootstrap) are not masked.

```groovy
implementation libs.observability
```

Adding the jar is enough: its `observability-defaults.yaml` loads itself below the service's own
configuration (ECS names for the trace, p95 histograms for requests and pool waits). Four variables
are required, with no default, so a service without them doesn't start (config, "Required
settings"): `OTLP_TRACES_ENDPOINT`, `OTLP_LOGS_ENDPOINT`, `OTLP_METRICS_ENDPOINT` and
`TRACING_SAMPLING`.

### Log masking (`org4j.observability.logging.masking`)

Nothing is masked unless the service lists it (the `masking` library):

| Property | What it masks |
|---|---|
| `fields` | values under these names (case-insensitive): 10 characters or more keep the first 6 and last 4 (`860030......7890`), shorter ones are masked whole (`...`) |
| `full-fields` | values under these names, whole, at most 10 mask characters; a name in both lists is masked whole |
| `values` | every match of these regular expressions, in any text, masked like `fields` |
| `mask-char` | the mask character, `.` by default |

```yaml
org4j:
  observability:
    logging:
      masking:
        fields: [pan, card]
        full-fields: [cvv, secret, access_token, authorization]
        values:
          - '(?<!\d)\d{13,19}(?!\d)'
```

Write `values` as a YAML list or indexed (`values[0]=…`, `…_VALUES_0_` in the environment): a
comma-separated string splits a `{13,19}`. From the environment or Vault the names are the relaxed
forms, `ORG4J_OBSERVABILITY_LOGGING_MASKING_FULL_FIELDS=cvv,secret`.

### Wire logging (`org4j.observability.logging.http`)

Every call a client built from Boot's `RestClient.Builder` makes is logged in at most three lines:
`→` with the masked URI and body, `←` with the status, time and masked answer, or `✗` when it
fails. With `enabled`, every request the service serves is logged the same way. Only a JSON, XML
or form body is logged, and only up to `max-body-bytes`; anything else is logged by its size, and
an answer too long to log streams through untouched.

| Property | |
|---|---|
| `enabled` | log the requests the service serves (client calls are logged either way); `false` by default |
| `max-body-bytes` | the longest body logged, both ways; 4000 by default |
| `include-headers` | headers logged with the request line; none by default. One named in the masking lists is masked by its rule |
| `exclude-paths` | Ant-style paths not logged at all (`/actuator/**`) |
| `no-body-paths` | Ant-style paths logged without bodies (`/v1/hsm/**`) |

A client call that sends or receives a secret sets the request attribute
`ObservabilityConstants.NO_BODIES` and is logged without either body (Keycloak's `/client-secret`
calls do). A client built on its own request factory (a SOAP one) adds the `HttpClientLogging` bean
itself.

### Metrics

- With the actuator, every health indicator is the gauge `org4j.observability.health.up{component}`
  (1 UP, 0 otherwise), so an alert can see a component go down.
- With the breaker library, every `Breaker` bean publishes its meters (`org4j.breaker.*`).

### Context across threads

`ContextPropagatingTaskDecorator` carries the trace and the logging MDC onto Spring's executors;
its static `wrap` methods do the same for a `Runnable`, `Callable` or `Executor` Spring does not
manage.

### Beans you can replace

`Masking` (the log masking), `HttpClientLogging`, `HttpServerLoggingFilter`, `RequestIdFilter`,
`HealthMetrics`, `BreakerMetrics`, the `TaskDecorator`.

## Tests

- `./gradlew test`: masked console, ECS and OTLP lines and stack traces; client and server wire
  logs (masking, headers, caps, bodies kept out); the properties; health and breaker gauges;
  context across threads.
- `./gradlew integrationTest`: Grafana's LGTM stack: one request's trace in Tempo and its masked
  log lines in Loki under the same trace id as `X-Request-Id`.
