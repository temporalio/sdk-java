package io.temporal.contrib.aws.s3driver;

import io.temporal.common.Experimental;
import io.temporal.common.converter.StorageDriver;
import io.temporal.common.converter.StorageDriverClaim;
import io.temporal.common.converter.StorageDriverException;
import io.temporal.common.converter.StorageDriverRetrieveContext;
import io.temporal.common.converter.StorageDriverStoreContext;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * {@link StorageDriver} implementation that stores payloads in Amazon S3. Uses content-addressed
 * storage with SHA-256 hashing for natural deduplication.
 *
 * <p>S3 object keys are constructed as:
 *
 * <pre>
 *   {keyPrefix}{namespace}/{workflowId}/{sha256-hash}
 * </pre>
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * software.amazon.awssdk.services.s3.S3Client awsS3 = S3Client.builder()
 *     .region(Region.US_EAST_1)
 *     .build();
 *
 * S3StorageDriver driver = new S3StorageDriver(
 *     S3StorageDriverOptions.newBuilder()
 *         .setClient(new AwsSdkV2S3Client(awsS3))
 *         .setBucket("my-temporal-payloads")
 *         .build());
 *
 * ExternalStorage externalStorage = ExternalStorage.newBuilder()
 *     .addDriver(driver)
 *     .build();
 * }</pre>
 *
 * <p><b>Important:</b> Temporal does not manage the lifecycle of stored objects. Users must
 * configure S3 Lifecycle Policies or similar mechanisms for cleanup. Objects should be retained at
 * least for the workflow lifetime plus the namespace retention period.
 */
@Experimental
public final class S3StorageDriver implements StorageDriver {

  private static final String DRIVER_TYPE = "aws.s3driver";
  static final String CLAIM_KEY_BUCKET = "bucket";
  static final String CLAIM_KEY_OBJECT_KEY = "key";

  private final S3Client client;
  private final String bucket;
  private final String keyPrefix;
  private final String driverName;

  /**
   * Creates a new S3 storage driver with the given options.
   *
   * @param options the driver configuration options
   */
  public S3StorageDriver(@Nonnull S3StorageDriverOptions options) {
    Objects.requireNonNull(options, "options");
    this.client = options.getClient();
    this.bucket = options.getBucket();
    this.keyPrefix = options.getKeyPrefix();
    this.driverName = options.getDriverName();
  }

  @Override
  public String name() {
    return driverName;
  }

  @Override
  public String type() {
    return DRIVER_TYPE;
  }

  @Override
  public List<StorageDriverClaim> store(StorageDriverStoreContext context, List<byte[]> payloads)
      throws StorageDriverException {
    List<StorageDriverClaim> claims = new ArrayList<>(payloads.size());
    for (byte[] payload : payloads) {
      String hash = sha256Hex(payload);
      String objectKey = buildObjectKey(context, hash);

      client.putObject(bucket, objectKey, payload);

      Map<String, String> claimData = new HashMap<>();
      claimData.put(CLAIM_KEY_BUCKET, bucket);
      claimData.put(CLAIM_KEY_OBJECT_KEY, objectKey);
      claims.add(new StorageDriverClaim(claimData));
    }
    return claims;
  }

  @Override
  public List<byte[]> retrieve(
      StorageDriverRetrieveContext context, List<StorageDriverClaim> claims)
      throws StorageDriverException {
    List<byte[]> results = new ArrayList<>(claims.size());
    for (StorageDriverClaim claim : claims) {
      String claimBucket = claim.getClaimData().get(CLAIM_KEY_BUCKET);
      String objectKey = claim.getClaimData().get(CLAIM_KEY_OBJECT_KEY);
      if (objectKey == null) {
        throw new StorageDriverException(
            "Claim is missing required '" + CLAIM_KEY_OBJECT_KEY + "' field: " + claim);
      }
      // Use the bucket from the claim if present, otherwise fall back to configured bucket.
      String effectiveBucket = claimBucket != null ? claimBucket : bucket;
      results.add(client.getObject(effectiveBucket, objectKey));
    }
    return results;
  }

  private String buildObjectKey(StorageDriverStoreContext context, String hash) {
    StringBuilder sb = new StringBuilder();
    if (!keyPrefix.isEmpty()) {
      sb.append(keyPrefix);
      if (!keyPrefix.endsWith("/")) {
        sb.append('/');
      }
    }
    sb.append(context.getNamespace()).append('/');
    sb.append(context.getWorkflowId()).append('/');
    if (context.getActivityType() != null) {
      sb.append(context.getActivityType()).append('/');
    }
    sb.append(hash);
    return sb.toString();
  }

  static String sha256Hex(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data);
      StringBuilder hexString = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new StorageDriverException("SHA-256 algorithm not available", e);
    }
  }
}
