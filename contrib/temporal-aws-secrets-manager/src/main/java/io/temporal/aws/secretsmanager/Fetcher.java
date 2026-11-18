package io.temporal.aws.secretsmanager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Fetches secret values by ID. Must be safe to call from multiple threads. */
interface Fetcher {
  String ENV_LAMBDA_EXTENSION_PORT = "PARAMETERS_SECRETS_EXTENSION_HTTP_PORT";
  String ENV_LAMBDA_FUNCTION_NAME = "AWS_LAMBDA_FUNCTION_NAME";
  String LAMBDA_EXTENSION_HOST = "localhost";
  int DEFAULT_LAMBDA_EXTENSION_PORT = 2773;

  /** Bounds each request made by the fetchers this package creates. */
  long FETCH_TIMEOUT_MILLIS = 10_000;

  /** The fetch_path metric label for this fetcher. */
  String path();

  /** Fetches the current value of the given secret. */
  SecretValue fetch(String secretId) throws Exception;

  /** A fetched secret value. */
  final class SecretValue {
    final byte[] value;
    final String versionId;

    SecretValue(byte[] value, String versionId) {
      this.value = value;
      this.versionId = versionId;
    }

    /** A secret value from a GetSecretValue response's fields, preferring SecretString. */
    static SecretValue fromResponse(String secretString, byte[] secretBinary, String versionId)
        throws FetchErrors.InvalidResponseException {
      byte[] value;
      if (secretString != null) {
        value = secretString.getBytes(StandardCharsets.UTF_8);
      } else if (secretBinary != null && secretBinary.length > 0) {
        value = secretBinary;
      } else {
        throw new FetchErrors.InvalidResponseException("secret has no value");
      }
      return new SecretValue(value, versionId != null ? versionId : "");
    }
  }

  /**
   * Returns a fetcher for the Lambda extension if it is detected, and otherwise an SDK fetcher with
   * a client created from the default AWS config.
   *
   * <p>The extension doesn't set PARAMETERS_SECRETS_EXTENSION_HTTP_PORT in the function's
   * environment unless the user configured it, so if it is unset and running in Lambda, the
   * extension's default port is probed. Setting it to empty disables detection, for when another
   * service uses the default port.
   */
  static Fetcher newDefaultFetcher(Map<String, String> processEnv) {
    String port = processEnv.get(ENV_LAMBDA_EXTENSION_PORT);
    if (port == null) {
      // Only try to auto-detect the Lambda extension if we're in a Lambda function
      String functionName = processEnv.get(ENV_LAMBDA_FUNCTION_NAME);
      if (functionName == null
          || functionName.isEmpty()
          || !probe(LAMBDA_EXTENSION_HOST, DEFAULT_LAMBDA_EXTENSION_PORT)) {
        return newDefaultSdkFetcher();
      }
      port = String.valueOf(DEFAULT_LAMBDA_EXTENSION_PORT);
    }
    if (port.isEmpty()) {
      return newDefaultSdkFetcher();
    }
    return new LambdaExtensionFetcher(port, processEnv);
  }

  /** Whether something accepts connections on the given port. */
  static boolean probe(String host, int port) {
    // Probing a local port is either accepted or refused almost immediately, so the timeout only
    // bounds pathological cases
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 100);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Whether the AWS SDK Secrets Manager client can be loaded. Checked without loading {@link
   * SdkFetcher}, which references AWS SDK classes.
   */
  static boolean isAwsSdkAvailable() {
    try {
      Class.forName(
          "software.amazon.awssdk.services.secretsmanager.SecretsManagerClient",
          false,
          Fetcher.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  static Fetcher newDefaultSdkFetcher() {
    if (!isAwsSdkAvailable()) {
      throw new IllegalStateException(
          "fetching secrets directly from AWS Secrets Manager requires the AWS SDK, since the AWS "
              + "Parameters and Secrets Lambda Extension was not detected (see "
              + ENV_LAMBDA_EXTENSION_PORT
              + "). Add a dependency on software.amazon.awssdk:secretsmanager, or add the extension "
              + "to your Lambda function");
    }
    return SdkFetcher.create(null);
  }
}
