package io.temporal.contrib.aws.s3driver;

import io.temporal.common.Experimental;
import io.temporal.common.converter.StorageDriverException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Adapter wrapping the AWS SDK for Java v2 S3 client into the {@link S3Client} interface used by
 * {@link S3StorageDriver}.
 *
 * <p><b>Retry behavior:</b> The AWS SDK v2 has built-in retry logic that is configurable on the
 * {@link software.amazon.awssdk.services.s3.S3Client} instance. Users <b>should</b> configure
 * appropriate retry policies for production use. For example:
 *
 * <pre>{@code
 * software.amazon.awssdk.services.s3.S3Client awsS3 =
 *     software.amazon.awssdk.services.s3.S3Client.builder()
 *         .region(Region.US_EAST_1)
 *         .overrideConfiguration(o -> o.retryPolicy(
 *             RetryPolicy.builder().numRetries(3).build()))
 *         .build();
 * S3Client client = new AwsSdkV2S3Client(awsS3);
 * }</pre>
 *
 * <p><b>Impact on workflows:</b> S3 failures during workflow replay will block workflow progress
 * until retries succeed or the operation is abandoned. Configure retry policies and timeouts
 * accordingly to avoid indefinite blocking.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * software.amazon.awssdk.services.s3.S3Client awsS3 = software.amazon.awssdk.services.s3.S3Client.builder()
 *     .region(Region.US_EAST_1)
 *     .build();
 * S3Client client = new AwsSdkV2S3Client(awsS3);
 * }</pre>
 */
@Experimental
public final class AwsSdkV2S3Client implements S3Client {

  private static final Logger log = LoggerFactory.getLogger(AwsSdkV2S3Client.class);

  private final software.amazon.awssdk.services.s3.S3Client delegate;

  /**
   * Creates a new adapter wrapping the given AWS SDK v2 S3 client.
   *
   * @param delegate the AWS SDK v2 S3 client to delegate to
   */
  public AwsSdkV2S3Client(software.amazon.awssdk.services.s3.S3Client delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public void putObject(String bucket, String key, byte[] data) {
    log.debug("S3 PutObject: bucket={}, key={}, size={} bytes", bucket, key, data.length);
    try {
      delegate.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(data));
    } catch (Exception e) {
      log.warn("S3 PutObject failed: bucket={}, key={}", bucket, key, e);
      throw new StorageDriverException(
          "Failed to upload object to S3: bucket=" + bucket + ", key=" + key, e);
    }
  }

  @Override
  public byte[] getObject(String bucket, String key) {
    log.debug("S3 GetObject: bucket={}, key={}", bucket, key);
    try (InputStream is =
        delegate.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        baos.write(buffer, 0, bytesRead);
      }
      return baos.toByteArray();
    } catch (Exception e) {
      log.warn("S3 GetObject failed: bucket={}, key={}", bucket, key, e);
      throw new StorageDriverException(
          "Failed to download object from S3: bucket=" + bucket + ", key=" + key, e);
    }
  }
}
