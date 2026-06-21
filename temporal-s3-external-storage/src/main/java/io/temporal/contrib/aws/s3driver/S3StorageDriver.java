package io.temporal.contrib.aws.s3driver;

import io.temporal.common.Experimental;
import io.temporal.common.converter.StorageDriver;
import io.temporal.common.converter.StorageDriverClaim;
import io.temporal.common.converter.StorageDriverException;
import io.temporal.common.converter.StorageDriverRetrieveContext;
import io.temporal.common.converter.StorageDriverStoreContext;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * @implNote Each payload is held in memory as a {@code byte[]}, so peak memory usage during store
 *     and retrieve operations is approximately 2–3× the payload size (original bytes, hash
 *     computation buffer, and S3 request/response buffer). S3 provides data integrity via checksums
 *     automatically. Content-addressed storage means that duplicate payloads produce identical
 *     object keys, making writes idempotent — re-uploading the same payload simply overwrites the
 *     same S3 object with identical content.
 */
@Experimental
public final class S3StorageDriver implements StorageDriver {

  private static final Logger log = LoggerFactory.getLogger(S3StorageDriver.class);

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

      log.debug(
          "Storing payload to S3: bucket={}, key={}, size={} bytes",
          bucket,
          objectKey,
          payload.length);
      try {
        client.putObject(bucket, objectKey, payload);
      } catch (StorageDriverException e) {
        log.warn("Failed to store payload to S3: bucket={}, key={}", bucket, objectKey, e);
        throw e;
      }

      Map<String, String> claimData = new HashMap<>();
      claimData.put(CLAIM_KEY_BUCKET, bucket);
      claimData.put(CLAIM_KEY_OBJECT_KEY, objectKey);
      claims.add(new StorageDriverClaim(claimData));
    }
    return claims;
  }

  /**
   * Retrieves payloads from S3 using the configured bucket.
   *
   * <p><b>Security note:</b> This method always uses the bucket configured at construction time
   * ({@code this.bucket}) and ignores any bucket value stored in claim data. Although claims
   * include a bucket field for informational purposes, honoring it at retrieval time would be a
   * confused-deputy vulnerability — an attacker who can craft or tamper with claim tokens could
   * redirect reads to an arbitrary bucket.
   */
  @Override
  public List<byte[]> retrieve(
      StorageDriverRetrieveContext context, List<StorageDriverClaim> claims)
      throws StorageDriverException {
    List<byte[]> results = new ArrayList<>(claims.size());
    for (StorageDriverClaim claim : claims) {
      String objectKey = claim.getClaimData().get(CLAIM_KEY_OBJECT_KEY);
      if (objectKey == null) {
        throw new StorageDriverException(
            "Claim is missing required '" + CLAIM_KEY_OBJECT_KEY + "' field: " + claim);
      }
      log.debug("Retrieving payload from S3: bucket={}, key={}", bucket, objectKey);
      // Always use the configured bucket for security — claim data could be tampered.
      try {
        results.add(client.getObject(bucket, objectKey));
      } catch (StorageDriverException e) {
        log.warn("Failed to retrieve payload from S3: bucket={}, key={}", bucket, objectKey, e);
        throw e;
      }
    }
    return results;
  }

  /**
   * Builds the S3 object key for a payload.
   *
   * <p>Each path segment derived from user-controlled input (namespace, workflowId, activityType)
   * is URL-encoded to prevent path-injection attacks (e.g., a workflowId containing {@code "../"}
   * could escape the intended key hierarchy).
   *
   * @param context the store context providing namespace, workflowId, and optional activityType
   * @param hash the SHA-256 hex digest of the payload
   * @return the fully-qualified S3 object key
   */
  private String buildObjectKey(StorageDriverStoreContext context, String hash) {
    StringBuilder sb = new StringBuilder();
    if (!keyPrefix.isEmpty()) {
      sb.append(keyPrefix);
      if (!keyPrefix.endsWith("/")) {
        sb.append('/');
      }
    }
    sb.append(encodePathSegment(context.getNamespace())).append('/');
    sb.append(encodePathSegment(context.getWorkflowId())).append('/');
    if (context.getActivityType() != null) {
      sb.append(encodePathSegment(context.getActivityType())).append('/');
    }
    sb.append(hash);
    return sb.toString();
  }

  /**
   * URL-encodes a single path segment, replacing {@code +} with {@code %20} so that spaces are
   * represented correctly in S3 object keys.
   */
  private static String encodePathSegment(String segment) {
    try {
      return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
    } catch (UnsupportedEncodingException e) {
      // UTF-8 is guaranteed to be available on all JVMs
      throw new AssertionError("UTF-8 encoding not available", e);
    }
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
