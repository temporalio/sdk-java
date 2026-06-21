package io.temporal.contrib.aws.s3driver;

import com.google.common.base.Preconditions;
import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configuration options for {@link S3StorageDriver}.
 *
 * <p>Example:
 *
 * <pre>{@code
 * S3StorageDriverOptions options = S3StorageDriverOptions.newBuilder()
 *     .setClient(new AwsSdkV2S3Client(awsS3Client))
 *     .setBucket("my-temporal-payloads")
 *     .setKeyPrefix("temporal/payloads/")
 *     .build();
 * }</pre>
 *
 * <p><b>Retry configuration:</b> The AWS SDK v2 has built-in retry logic that is configurable on
 * the {@code S3Client} passed to {@link AwsSdkV2S3Client}. Users <b>should</b> configure
 * appropriate retry policies for production use. For example:
 *
 * <pre>{@code
 * S3Client.builder()
 *     .overrideConfiguration(o -> o.retryPolicy(
 *         RetryPolicy.builder().numRetries(3).build()))
 *     .build();
 * }</pre>
 *
 * <p>S3 failures during workflow replay will block workflow progress until retries succeed.
 *
 * <p><b>Data confidentiality:</b> Payload data is stored as-is in S3. To protect data at rest,
 * configure S3 bucket-level encryption (SSE-S3 or SSE-KMS) on the target bucket.
 *
 * <p><b>Data cleanup:</b> Temporal does not manage the lifecycle of stored S3 objects. Configure S3
 * Lifecycle Policies on the target bucket to automatically expire or transition objects. Objects
 * should be retained at least for the workflow lifetime plus the namespace retention period.
 */
@Experimental
public final class S3StorageDriverOptions {

  private static final String DEFAULT_DRIVER_NAME = "aws.s3driver";

  private final S3Client client;
  private final String bucket;
  private final String keyPrefix;
  private final String driverName;

  private S3StorageDriverOptions(
      S3Client client, String bucket, String keyPrefix, String driverName) {
    this.client = client;
    this.bucket = bucket;
    this.keyPrefix = keyPrefix;
    this.driverName = driverName;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public S3Client getClient() {
    return client;
  }

  public String getBucket() {
    return bucket;
  }

  @Nonnull
  public String getKeyPrefix() {
    return keyPrefix;
  }

  @Nonnull
  public String getDriverName() {
    return driverName;
  }

  public static final class Builder {
    private S3Client client;
    private String bucket;
    private String keyPrefix = "";
    private String driverName = DEFAULT_DRIVER_NAME;

    private Builder() {}

    /** Sets the S3 client to use for storage operations. Required. */
    public Builder setClient(@Nonnull S3Client client) {
      this.client = Objects.requireNonNull(client, "client");
      return this;
    }

    /** Sets the S3 bucket name. Required. */
    public Builder setBucket(@Nonnull String bucket) {
      this.bucket = Objects.requireNonNull(bucket, "bucket");
      return this;
    }

    /**
     * Sets an optional prefix prepended to all S3 object keys. Useful for organizing payloads
     * within a shared bucket. Default is empty string (no prefix).
     */
    public Builder setKeyPrefix(@Nullable String keyPrefix) {
      this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
      return this;
    }

    /**
     * Sets the driver instance name. This is stored in claim tokens. Default is "aws.s3driver".
     * Changing this after payloads are stored will break retrieval.
     */
    public Builder setDriverName(@Nonnull String driverName) {
      this.driverName = Objects.requireNonNull(driverName, "driverName");
      return this;
    }

    public S3StorageDriverOptions build() {
      Preconditions.checkState(client != null, "S3Client must be set");
      Preconditions.checkState(bucket != null && !bucket.isEmpty(), "Bucket must be set");
      return new S3StorageDriverOptions(client, bucket, keyPrefix, driverName);
    }
  }
}
