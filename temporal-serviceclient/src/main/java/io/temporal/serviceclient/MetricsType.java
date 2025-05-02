package io.temporal.serviceclient;

public final class MetricsType {
  private MetricsType() {}

  public static final String TEMPORAL_METRICS_PREFIX = "temporal_";

  public static final String TEMPORAL_REQUEST = TEMPORAL_METRICS_PREFIX + "request";
  public static final String TEMPORAL_REQUEST_FAILURE = TEMPORAL_REQUEST + "_failure";
  public static final String TEMPORAL_REQUEST_LATENCY = TEMPORAL_REQUEST + "_latency";
  public static final String TEMPORAL_LONG_REQUEST = TEMPORAL_METRICS_PREFIX + "long_request";
  public static final String TEMPORAL_LONG_REQUEST_FAILURE = TEMPORAL_LONG_REQUEST + "_failure";
  public static final String TEMPORAL_LONG_REQUEST_LATENCY = TEMPORAL_LONG_REQUEST + "_latency";
}
