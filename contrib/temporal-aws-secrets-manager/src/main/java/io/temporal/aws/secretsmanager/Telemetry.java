package io.temporal.aws.secretsmanager;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Metrics for a client's metric scope. Metric and label names must be the same across SDKs. */
class Telemetry {
  private static final Logger log = LoggerFactory.getLogger(Telemetry.class);

  static final String METRIC_FETCH_FAILED = "temporal_aws_secrets_manager_fetch_failed";
  static final String METRIC_FETCH_LATENCY = "temporal_aws_secrets_manager_fetch_latency";
  static final String METRIC_CACHE_LOOKUP = "temporal_aws_secrets_manager_cache_lookup";

  static final String LABEL_SECRET_TYPE = "secret_type";
  static final String LABEL_FETCH_PATH = "fetch_path";
  static final String LABEL_FAILURE_REASON = "failure_reason";
  static final String LABEL_FALLBACK = "fallback";
  static final String LABEL_OUTCOME = "outcome";
  static final String LABEL_RESULT = "result";

  // secret_type values. Client cert and key secrets are fetched together and reported as
  // tls_client_cert in metrics.
  static final String SECRET_TYPE_API_KEY = "api_key";
  static final String SECRET_TYPE_TLS_CLIENT_CERT = "tls_client_cert";
  static final String SECRET_TYPE_TLS_CLIENT_KEY = "tls_client_key";
  static final String SECRET_TYPE_TLS_SERVER_CA_CERT = "tls_server_ca_cert";

  // fetch_path values: the Lambda extension, or a direct fetch with the AWS SDK
  static final String FETCH_PATH_EXTENSION = "extension";
  static final String FETCH_PATH_SDK = "sdk";

  // AWS error codes mapped to failure_reason values. Other codes are "other".
  private static final Map<String, String> AWS_ERROR_CODE_FAILURE_REASONS = new HashMap<>();

  static {
    AWS_ERROR_CODE_FAILURE_REASONS.put("ResourceNotFoundException", "resource_not_found");
    AWS_ERROR_CODE_FAILURE_REASONS.put("AccessDeniedException", "access_denied");
    AWS_ERROR_CODE_FAILURE_REASONS.put("DecryptionFailure", "decryption_failure");
    AWS_ERROR_CODE_FAILURE_REASONS.put("InvalidRequestException", "invalid_request");
    AWS_ERROR_CODE_FAILURE_REASONS.put("InvalidParameterException", "invalid_request");
    AWS_ERROR_CODE_FAILURE_REASONS.put("ThrottlingException", "throttling");
    AWS_ERROR_CODE_FAILURE_REASONS.put("InternalServiceError", "internal_service_error");
  }

  private final Scope scope;

  Telemetry(@Nullable Scope scope) {
    this.scope = scope != null ? scope : new NoopScope();
  }

  /** Returns the failure_reason metric label for a fetch error. */
  static String classifyFailure(Throwable err) {
    if (err instanceof FetchErrors.InvalidResponseException) {
      return "invalid_response";
    }
    if (err instanceof FetchErrors.LambdaExtensionStatusException) {
      int status = ((FetchErrors.LambdaExtensionStatusException) err).status;
      if (status >= 400 && status < 500) {
        return "client_error";
      }
      if (status >= 500 && status < 600) {
        return "server_error";
      }
      return "other";
    }
    if (err instanceof FetchErrors.FetchTimeoutException) {
      return "timeout";
    }
    if (err instanceof FetchErrors.AwsErrorException) {
      String code = ((FetchErrors.AwsErrorException) err).errorCode;
      String reason = code != null ? AWS_ERROR_CODE_FAILURE_REASONS.get(code) : null;
      return reason != null ? reason : "other";
    }
    // The Lambda extension doesn't return the underlying AWS error, only an HTTP status with a
    // generic message, so its errors are only classified by status class
    return "other";
  }

  void cacheLookup(Map<String, String> tags) {
    record(() -> scope.tagged(tags).counter(METRIC_CACHE_LOOKUP).inc(1));
  }

  void fetchFailed(Map<String, String> tags) {
    record(() -> scope.tagged(tags).counter(METRIC_FETCH_FAILED).inc(1));
  }

  void fetchLatency(Map<String, String> tags, long nanos) {
    record(
        () ->
            scope
                .tagged(tags)
                .timer(METRIC_FETCH_LATENCY)
                .record(com.uber.m3.util.Duration.ofNanos(nanos)));
  }

  /** Records a metric, never throwing, since recording failures shouldn't affect fetches. */
  private static void record(Runnable fn) {
    try {
      fn.run();
    } catch (RuntimeException e) {
      log.warn("Failed recording AWS Secrets Manager metric", e);
    }
  }
}
