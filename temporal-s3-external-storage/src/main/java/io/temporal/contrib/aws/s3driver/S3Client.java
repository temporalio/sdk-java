package io.temporal.contrib.aws.s3driver;

import io.temporal.common.Experimental;

/**
 * Abstraction over S3 client operations used by {@link S3StorageDriver}. This interface allows
 * plugging in different S3 client implementations and enables testing with mocks.
 *
 * @see AwsSdkV2S3Client
 */
@Experimental
public interface S3Client {
  /**
   * Uploads an object to S3.
   *
   * @param bucket the S3 bucket name
   * @param key the S3 object key
   * @param data the object content
   * @throws io.temporal.common.converter.StorageDriverException if the upload fails
   */
  void putObject(String bucket, String key, byte[] data);

  /**
   * Downloads an object from S3.
   *
   * @param bucket the S3 bucket name
   * @param key the S3 object key
   * @return the object content
   * @throws io.temporal.common.converter.StorageDriverException if the download fails
   */
  byte[] getObject(String bucket, String key);
}
