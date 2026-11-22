package io.github.org4j.observability;

/** Names a service or a dashboard can rely on. */
public final class ObservabilityConstants {

  /** The gauge per health indicator: 1 while UP, 0 otherwise. */
  public static final String HEALTH_METRIC = "org4j.observability.health.up";

  /** The tag naming the health indicator on {@link #HEALTH_METRIC}. */
  public static final String COMPONENT_TAG = "component";

  /**
   * A request attribute: a client call that carries it is logged without its request and response
   * bodies, for calls that send or receive a secret.
   */
  public static final String NO_BODIES = "org4j.observability.http-client-logging.no-bodies";

  /** The response header that names the request's trace id. */
  public static final String REQUEST_ID_HEADER = "X-Request-Id";

  private ObservabilityConstants() {}
}
