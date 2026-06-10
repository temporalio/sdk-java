package io.temporal.payload.storage.s3;

import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.payload.storage.PayloadHasher;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;

/**
 * {@link StorageDriver} that stores payloads in Amazon S3 under content-addressable keys derived
 * from the SHA-256 hash of the serialized payload.
 *
 * <p>Construct via {@link #newBuilder()}.
 */
@Experimental
public final class S3StorageDriver implements StorageDriver {
  private static final String DRIVER_TYPE = "aws.s3driver";
  private static final String DEFAULT_DRIVER_NAME = "aws.s3driver";
  private static final int DEFAULT_MAX_PAYLOAD_SIZE = 50 * 1024 * 1024;
  private static final String HASH_ALGORITHM = "sha256";

  private static final String CLAIM_BUCKET = "bucket";
  private static final String CLAIM_KEY = "key";
  private static final String CLAIM_HASH_ALGORITHM = "hash_algorithm";
  private static final String CLAIM_HASH_VALUE = "hash_value";

  public static Builder newBuilder() {
    return new Builder();
  }

  private final @Nonnull S3Client client;
  private final @Nonnull BucketResolver bucketResolver;
  private final @Nonnull String name;
  private final int maxPayloadSize;

  private S3StorageDriver(
      @Nonnull S3Client client,
      @Nonnull BucketResolver bucketResolver,
      @Nonnull String name,
      int maxPayloadSize) {
    this.client = client;
    this.bucketResolver = bucketResolver;
    this.name = name;
    this.maxPayloadSize = maxPayloadSize;
  }

  @Nonnull
  @Override
  public String getName() {
    return name;
  }

  @Nonnull
  @Override
  public String getType() {
    return DRIVER_TYPE;
  }

  @Nonnull
  @Override
  public CompletableFuture<List<StorageDriverClaim>> store(
      @Nonnull StorageDriverStoreContext context, @Nonnull List<Payload> payloads) {
    for (Payload payload : payloads) {
      int size = payload.getSerializedSize();
      if (size > maxPayloadSize) {
        return failedFuture(
            new S3StorageException("payload size " + size + " exceeds maximum " + maxPayloadSize));
      }
    }

    StorageDriverTargetInfo target = context.getTarget();
    String describeSuffix = describeSuffix();
    List<CompletableFuture<StorageDriverClaim>> claimFutures = new ArrayList<>(payloads.size());
    for (Payload payload : payloads) {
      byte[] data = payload.toByteArray();
      String hexDigest = PayloadHasher.sha256Hex(data);
      String bucket = bucketResolver.resolveBucket(context, payload);
      String key = S3StorageKey.forPayload(target, HASH_ALGORITHM, hexDigest);
      String location = storageLocation(bucket, key, describeSuffix);
      claimFutures.add(
          withFailureContext(client.objectExists(bucket, key), "existence check failed " + location)
              .thenCompose(
                  exists ->
                      exists
                          ? CompletableFuture.<Void>completedFuture(null)
                          : withFailureContext(
                              client.putObject(bucket, key, data), "upload failed " + location))
              .thenApply(ignored -> claimFor(bucket, key, hexDigest)));
    }
    return CompletableFutures.allOf(claimFutures);
  }

  @Nonnull
  @Override
  public CompletableFuture<List<Payload>> retrieve(
      @Nonnull StorageDriverRetrieveContext context, @Nonnull List<StorageDriverClaim> claims) {
    String describeSuffix = describeSuffix();
    List<CompletableFuture<Payload>> payloadFutures = new ArrayList<>(claims.size());
    for (StorageDriverClaim claim : claims) {
      Map<String, String> claimData = claim.getClaimData();
      String bucket = claimData.get(CLAIM_BUCKET);
      if (bucket == null) {
        payloadFutures.add(failedFuture(missingField(CLAIM_BUCKET)));
        continue;
      }
      String key = claimData.get(CLAIM_KEY);
      if (key == null) {
        payloadFutures.add(failedFuture(missingField(CLAIM_KEY)));
        continue;
      }
      String location = storageLocation(bucket, key, describeSuffix);
      CompletableFuture<Payload> payloadFuture =
          withFailureContext(client.getObject(bucket, key), "download failed " + location)
              .thenApply(data -> verifyAndParse(claimData, bucket, key, data));
      payloadFutures.add(payloadFuture);
    }
    return CompletableFutures.allOf(payloadFutures);
  }

  private StorageDriverClaim claimFor(String bucket, String key, String hexDigest) {
    Map<String, String> claimData = new HashMap<>();
    claimData.put(CLAIM_BUCKET, bucket);
    claimData.put(CLAIM_KEY, key);
    claimData.put(CLAIM_HASH_ALGORITHM, HASH_ALGORITHM);
    claimData.put(CLAIM_HASH_VALUE, hexDigest);
    return new StorageDriverClaim(claimData);
  }

  private Payload verifyAndParse(
      Map<String, String> claimData, String bucket, String key, byte[] data) {
    String algorithm = claimData.get(CLAIM_HASH_ALGORITHM);
    if (algorithm == null) {
      throw missingField(CLAIM_HASH_ALGORITHM);
    }
    if (!HASH_ALGORITHM.equals(algorithm)) {
      throw new S3StorageException("unsupported hash algorithm \"" + algorithm + "\"");
    }
    String expectedHash = claimData.get(CLAIM_HASH_VALUE);
    if (expectedHash == null) {
      throw missingField(CLAIM_HASH_VALUE);
    }
    String actualHash = PayloadHasher.sha256Hex(data);
    if (!actualHash.equals(expectedHash)) {
      throw new S3StorageException(
          "integrity check failed [bucket="
              + bucket
              + ", key="
              + key
              + "]: expected hash "
              + expectedHash
              + ", got "
              + actualHash);
    }
    try {
      return Payload.parseFrom(data);
    } catch (InvalidProtocolBufferException e) {
      throw new S3StorageException(
          "failed to unmarshal payload [bucket=" + bucket + ", key=" + key + "]", e);
    }
  }

  private static String storageLocation(String bucket, String key, String describeSuffix) {
    return "[bucket=" + bucket + ", key=" + key + describeSuffix + "]";
  }

  /** Renders {@link S3Client#describe()} as a {@code ", k=v"} suffix for failure messages. */
  private String describeSuffix() {
    Map<String, String> describe = client.describe();
    if (describe == null || describe.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, String> entry : describe.entrySet()) {
      sb.append(", ").append(entry.getKey()).append("=").append(entry.getValue());
    }
    return sb.toString();
  }

  private static S3StorageException missingField(String field) {
    return new S3StorageException("claim missing field \"" + field + "\"");
  }

  private static <T> CompletableFuture<T> withFailureContext(
      CompletableFuture<T> future, String failureMessage) {
    return future.handle(
        (value, ex) -> {
          if (ex == null) {
            return value;
          }
          Throwable cause = unwrap(ex);
          String causeMessage = cause.getMessage() != null ? cause.getMessage() : cause.toString();
          throw new S3StorageException(failureMessage + ": " + causeMessage, cause);
        });
  }

  private static <T> CompletableFuture<T> failedFuture(Throwable t) {
    CompletableFuture<T> future = new CompletableFuture<>();
    future.completeExceptionally(t);
    return future;
  }

  private static Throwable unwrap(Throwable t) {
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    return t;
  }

  public static final class Builder {
    private S3Client client;
    private String staticBucket;
    private BucketResolver bucketResolver;
    private String name = DEFAULT_DRIVER_NAME;
    private int maxPayloadSize = DEFAULT_MAX_PAYLOAD_SIZE;

    private Builder() {}

    /** Required. The S3 client used for storage operations. */
    public Builder setClient(@Nonnull S3Client client) {
      this.client = Objects.requireNonNull(client, "client");
      return this;
    }

    /**
     * Stores every payload in a fixed bucket. Mutually exclusive with {@link #setBucketResolver}:
     * setting both before {@link #build()} is an error.
     */
    public Builder setBucket(@Nonnull String bucket) {
      this.staticBucket = Objects.requireNonNull(bucket, "bucket");
      return this;
    }

    /**
     * Selects the bucket per payload. Mutually exclusive with {@link #setBucket} and setting both
     * before {@link #build()} will throw.
     */
    public Builder setBucketResolver(@Nonnull BucketResolver bucketResolver) {
      this.bucketResolver = Objects.requireNonNull(bucketResolver, "bucketResolver");
      return this;
    }

    /**
     * Stable, unique identifier for this driver instance. Defaults to {@code "aws.s3driver"};
     * override it when registering multiple S3 drivers with distinct configurations.
     */
    public Builder setName(@Nonnull String name) {
      this.name = Objects.requireNonNull(name, "name");
      return this;
    }

    /**
     * Maximum serialized payload size in bytes the driver accepts. Defaults to 50 MiB. Storing a
     * larger payload fails the {@code store} call.
     */
    public Builder setMaxPayloadSize(int maxPayloadSize) {
      this.maxPayloadSize = maxPayloadSize;
      return this;
    }

    public S3StorageDriver build() {
      if (client == null) {
        throw new IllegalStateException("client is required");
      }
      if (staticBucket != null && bucketResolver != null) {
        throw new IllegalStateException("setBucket and setBucketResolver are mutually exclusive");
      }
      BucketResolver resolver = bucketResolver;
      if (resolver == null && staticBucket != null) {
        String bucket = staticBucket;
        resolver = (context, payload) -> bucket;
      }
      if (resolver == null) {
        throw new IllegalStateException("a bucket or bucket resolver is required");
      }
      if (maxPayloadSize <= 0) {
        throw new IllegalStateException("maxPayloadSize must be positive, got " + maxPayloadSize);
      }
      return new S3StorageDriver(client, resolver, name, maxPayloadSize);
    }
  }
}
