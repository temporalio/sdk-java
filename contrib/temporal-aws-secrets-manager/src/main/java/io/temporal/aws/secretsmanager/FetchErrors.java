package io.temporal.aws.secretsmanager;

/** Errors from fetching secrets, classified by {@link Telemetry#classifyFailure(Throwable)}. */
final class FetchErrors {
  private FetchErrors() {}

  /** A response that was received but has no usable secret value. */
  static final class InvalidResponseException extends Exception {
    InvalidResponseException(String message) {
      super(message);
    }

    InvalidResponseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A non-200 response from the Lambda extension. */
  static final class LambdaExtensionStatusException extends Exception {
    final int status;

    LambdaExtensionStatusException(int status, String body) {
      super("AWS Parameters and Secrets Lambda Extension returned status " + status + ": " + body);
      this.status = status;
    }
  }

  /** A fetch that took longer than the fetch timeout. */
  static final class FetchTimeoutException extends Exception {
    FetchTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * An error response from Secrets Manager, with its AWS error code. Wraps the AWS SDK's exception
   * so that it can be classified without loading AWS SDK classes.
   */
  static final class AwsErrorException extends Exception {
    final String errorCode;

    AwsErrorException(String errorCode, Throwable cause) {
      super(cause.getMessage(), cause);
      this.errorCode = errorCode;
    }
  }
}
