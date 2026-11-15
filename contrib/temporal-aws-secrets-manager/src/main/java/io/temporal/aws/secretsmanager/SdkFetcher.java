package io.temporal.aws.secretsmanager;

import java.time.Duration;
import javax.annotation.Nullable;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

/**
 * Fetches secrets directly from Secrets Manager with the AWS SDK, which is an optional dependency.
 * This is the only class that references AWS SDK classes, so it must only be loaded when the AWS
 * SDK is available.
 */
final class SdkFetcher implements Fetcher {
  private static final AwsRequestOverrideConfiguration TIMEOUT =
      AwsRequestOverrideConfiguration.builder()
          .apiCallTimeout(Duration.ofMillis(FETCH_TIMEOUT_MILLIS))
          .build();

  private final Object lock = new Object();
  private SecretsManagerClient client;

  private SdkFetcher(@Nullable SecretsManagerClient client) {
    this.client = client;
  }

  /**
   * Creates a fetcher with the given client, which must be a {@code SecretsManagerClient}, or with
   * a client created from the default AWS config on first use if null.
   */
  static SdkFetcher create(@Nullable Object client) {
    return new SdkFetcher((SecretsManagerClient) client);
  }

  @Override
  public String path() {
    return Telemetry.FETCH_PATH_SDK;
  }

  @Override
  public SecretValue fetch(String secretId) throws Exception {
    GetSecretValueResponse resp;
    try {
      resp =
          getClient()
              .getSecretValue(
                  GetSecretValueRequest.builder()
                      .secretId(secretId)
                      .overrideConfiguration(TIMEOUT)
                      .build());
    } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException e) {
      throw new FetchErrors.FetchTimeoutException(
          "AWS Secrets Manager secret fetch timed out after " + FETCH_TIMEOUT_MILLIS + "ms", e);
    } catch (AwsServiceException e) {
      throw new FetchErrors.AwsErrorException(
          e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null, e);
    }
    SdkBytes binary = resp.secretBinary();
    return SecretValue.fromResponse(
        resp.secretString(), binary != null ? binary.asByteArray() : null, resp.versionId());
  }

  private SecretsManagerClient getClient() {
    synchronized (lock) {
      if (client == null) {
        client = SecretsManagerClient.create();
      }
      return client;
    }
  }
}
