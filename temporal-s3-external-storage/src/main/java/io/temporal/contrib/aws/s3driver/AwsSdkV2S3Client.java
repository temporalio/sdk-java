package io.temporal.contrib.aws.s3driver;

import io.temporal.common.Experimental;
import io.temporal.common.converter.StorageDriverException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Adapter wrapping the AWS SDK for Java v2 S3 client into the {@link S3Client} interface used by
 * {@link S3StorageDriver}.
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
    try {
      delegate.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(data));
    } catch (Exception e) {
      throw new StorageDriverException(
          "Failed to upload object to S3: bucket=" + bucket + ", key=" + key, e);
    }
  }

  @Override
  public byte[] getObject(String bucket, String key) {
    try (InputStream is =
        delegate.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        baos.write(buffer, 0, bytesRead);
      }
      return baos.toByteArray();
    } catch (IOException e) {
      throw new StorageDriverException(
          "Failed to download object from S3: bucket=" + bucket + ", key=" + key, e);
    } catch (Exception e) {
      throw new StorageDriverException(
          "Failed to download object from S3: bucket=" + bucket + ", key=" + key, e);
    }
  }
}
