package io.temporal.payload.storage.gcsdriver;

import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

/**
 * Stores complete serialized Temporal payloads in flat namespace GCS buckets using conditional
 * creation and SHA-256 content addressing. Downloads are verified before parsing.
 *
 * <p>Construct via {@link #newBuilder()}. The application owns the provider client and its
 * resources.
 */
@Experimental
public final class GcsStorageDriver implements StorageDriver {
  private static final String DRIVER_TYPE = "gcp.gcsdriver";
  private static final String HASH_ALGORITHM = "sha256";
  private static final int DEFAULT_MAX_PAYLOAD_SIZE = 50 * 1024 * 1024;
  private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

  private final GcsStorageDriverClient client;
  private final BucketResolver bucketResolver;
  private final String name;
  private final int maxPayloadSize;

  private GcsStorageDriver(Builder builder) {
    client = builder.client;
    bucketResolver = builder.bucketResolver;
    name = builder.name;
    maxPayloadSize = builder.maxPayloadSize;
  }

  public static Builder newBuilder() {
    return new Builder();
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
    List<PreparedPayload> prepared = new ArrayList<>(payloads.size());
    try {
      for (Payload payload : payloads) {
        int size = payload.getSerializedSize();
        if (size > maxPayloadSize) {
          throw new GcsStorageException(
              "payload size " + size + " exceeds maximum " + maxPayloadSize);
        }
      }
      for (Payload payload : payloads) {
        String bucket = bucketResolver.resolveBucket(context, payload);
        if (bucket == null || bucket.isEmpty()) {
          throw new GcsStorageException("bucket resolver returned an empty bucket");
        }
        byte[] data = payload.toByteArray();
        String hash = PayloadHasher.sha256Hex(data);
        String objectName = GcsStorageKey.forPayload(context.getTarget(), hash);
        prepared.add(new PreparedPayload(bucket, data, objectName, hash));
      }
    } catch (RuntimeException e) {
      return CompletableFutures.failedFuture(e);
    }
    String describeSuffix = describeSuffix();
    return CompletableFutures.mapAsync(
        prepared,
        context.getCancellationToken(),
        payload -> uploadIfMissing(payload, describeSuffix));
  }

  /**
   * Uploads a payload unless its object already exists, which is common during replay. Cancelling
   * the returned future cancels whichever request is in flight.
   */
  private CompletableFuture<StorageDriverClaim> uploadIfMissing(
      PreparedPayload payload, String describeSuffix) {
    String bucket = payload.bucket;
    String location = requestLocation(bucket, payload.objectName, describeSuffix);
    StorageDriverClaim claim = claimFor(bucket, payload.objectName, payload.hash);
    CompletableFuture<StorageDriverClaim> result = new CompletableFuture<>();
    CompletableFuture<Boolean> existenceCheck =
        request(
            () -> client.objectExists(bucket, payload.objectName),
            "existence check failed " + location,
            Function.identity());
    AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>(existenceCheck);
    result.whenComplete(
        (value, error) -> {
          if (result.isCancelled()) {
            inFlight.get().cancel(true);
          }
        });
    existenceCheck.whenComplete(
        (exists, error) -> {
          if (error != null) {
            result.completeExceptionally(CompletableFutures.unwrap(error));
            return;
          }
          if (Boolean.TRUE.equals(exists)) {
            result.complete(claim);
            return;
          }
          CompletableFuture<StorageDriverClaim> upload =
              request(
                  () -> client.putObjectIfAbsent(bucket, payload.objectName, payload.data),
                  "upload failed " + location,
                  ignored -> claim);
          inFlight.set(upload);
          // The result may have been cancelled before the upload was recorded as in flight.
          if (result.isCancelled()) {
            upload.cancel(true);
          }
          upload.whenComplete(
              (value, uploadError) -> {
                if (uploadError == null) {
                  result.complete(value);
                } else {
                  result.completeExceptionally(CompletableFutures.unwrap(uploadError));
                }
              });
        });
    return result;
  }

  @Nonnull
  @Override
  public CompletableFuture<List<Payload>> retrieve(
      @Nonnull StorageDriverRetrieveContext context, @Nonnull List<StorageDriverClaim> claims) {
    List<Map<String, String>> validated = new ArrayList<>(claims.size());
    try {
      for (StorageDriverClaim claim : claims) {
        Map<String, String> data = new HashMap<>(claim.getClaimData());
        String claimBucket = requiredField(data, "bucket");
        String objectName = requiredField(data, "object_name");
        String algorithm = requiredField(data, "hash_algorithm");
        if (!HASH_ALGORITHM.equals(algorithm)) {
          throw new GcsStorageException("unsupported hash algorithm \"" + algorithm + "\"");
        }
        String hash = requiredField(data, "hash_value");
        if (!SHA256_HEX.matcher(hash).matches()) {
          throw new GcsStorageException(
              "invalid SHA-256 hash_value " + location(claimBucket, objectName));
        }
        validated.add(data);
      }
    } catch (RuntimeException e) {
      return CompletableFutures.failedFuture(e);
    }
    String describeSuffix = describeSuffix();
    return CompletableFutures.mapAsync(
        validated,
        context.getCancellationToken(),
        claim -> {
          String claimBucket = claim.get("bucket");
          String objectName = claim.get("object_name");
          return request(
              () -> client.getObject(claimBucket, objectName),
              "download failed " + requestLocation(claimBucket, objectName, describeSuffix),
              data -> verifyAndParse(data, claim));
        });
  }

  private static StorageDriverClaim claimFor(String bucket, String objectName, String hash) {
    Map<String, String> data = new HashMap<>();
    data.put("bucket", bucket);
    data.put("object_name", objectName);
    data.put("hash_algorithm", HASH_ALGORITHM);
    data.put("hash_value", hash);
    return new StorageDriverClaim(data);
  }

  private static String requiredField(Map<String, String> claim, String field) {
    String value = claim.get(field);
    if (value == null || value.isEmpty()) {
      throw new GcsStorageException("claim missing or empty field \"" + field + "\"");
    }
    return value;
  }

  private static Payload verifyAndParse(byte[] data, Map<String, String> claim) {
    String location = location(claim.get("bucket"), claim.get("object_name"));
    String actualHash = PayloadHasher.sha256Hex(data);
    String expectedHash = claim.get("hash_value");
    if (!actualHash.equals(expectedHash)) {
      throw new GcsStorageException(
          "integrity check failed "
              + location
              + ": expected hash "
              + expectedHash
              + ", got "
              + actualHash);
    }
    try {
      return Payload.parseFrom(data);
    } catch (InvalidProtocolBufferException e) {
      throw new GcsStorageException("failed to unmarshal payload " + location, e);
    }
  }

  private static String location(String bucket, String objectName) {
    return requestLocation(bucket, objectName, "");
  }

  private static String requestLocation(String bucket, String objectName, String describeSuffix) {
    return "[bucket=" + bucket + ", object_name=" + objectName + describeSuffix + "]";
  }

  /**
   * Renders {@link GcsStorageDriverClient#describe()} as a {@code ", k=v"} suffix for request
   * failure messages.
   */
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

  private static <I, O> CompletableFuture<O> request(
      Supplier<CompletableFuture<I>> start, String failureMessage, Function<I, O> transform) {
    CompletableFuture<I> request;
    try {
      request = start.get();
    } catch (RuntimeException e) {
      return CompletableFutures.failedFuture(withContext(failureMessage, e));
    }
    CompletableFuture<O> result =
        request.handle(
            (value, error) -> {
              if (error != null) {
                throw withContext(failureMessage, error);
              }
              return transform.apply(value);
            });
    result.whenComplete(
        (value, error) -> {
          if (result.isCancelled()) {
            request.cancel(true);
          }
        });
    return result;
  }

  private static GcsStorageException withContext(String message, Throwable error) {
    Throwable cause = CompletableFutures.unwrap(error);
    return new GcsStorageException(message + ": " + cause.getMessage(), cause);
  }

  private static final class PreparedPayload {
    final String bucket;
    final byte[] data;
    final String objectName;
    final String hash;

    PreparedPayload(String bucket, byte[] data, String objectName, String hash) {
      this.bucket = bucket;
      this.data = data;
      this.objectName = objectName;
      this.hash = hash;
    }
  }

  @Experimental
  public static final class Builder {
    private GcsStorageDriverClient client;
    private BucketResolver bucketResolver;
    private String name = DRIVER_TYPE;
    private int maxPayloadSize = DEFAULT_MAX_PAYLOAD_SIZE;

    private Builder() {}

    /** Required. The client used for storage operations. */
    public Builder setClient(@Nonnull GcsStorageDriverClient client) {
      this.client = Objects.requireNonNull(client, "client");
      return this;
    }

    /**
     * Stores every payload in a fixed, nonempty bucket configured with a flat namespace.
     * Convenience for a {@link BucketResolver} that always returns {@code bucket}. The last of
     * {@code setBucket}/{@code setBucketResolver} wins.
     */
    public Builder setBucket(@Nonnull String bucket) {
      String fixed = nonempty(bucket, "bucket");
      this.bucketResolver = (context, payload) -> fixed;
      return this;
    }

    /**
     * Selects the upload bucket per payload. Buckets must use a flat namespace. Downloads use the
     * bucket recorded in each reference. The last of {@code setBucket}/{@code setBucketResolver}
     * wins.
     */
    public Builder setBucketResolver(@Nonnull BucketResolver bucketResolver) {
      this.bucketResolver = Objects.requireNonNull(bucketResolver, "bucketResolver");
      return this;
    }

    /**
     * Nonempty, stable identifier for this driver. Defaults to {@code "gcp.gcsdriver"}. Keep the
     * name registered on clients and workers for as long as stored references exist.
     */
    public Builder setName(@Nonnull String name) {
      this.name = nonempty(name, "name");
      return this;
    }

    /** Maximum serialized upload size in bytes. Must be positive; defaults to 50 MiB. */
    public Builder setMaxPayloadSize(int maxPayloadSize) {
      if (maxPayloadSize <= 0) {
        throw new IllegalArgumentException(
            "maxPayloadSize must be positive, got " + maxPayloadSize);
      }
      this.maxPayloadSize = maxPayloadSize;
      return this;
    }

    public GcsStorageDriver build() {
      if (client == null) {
        throw new IllegalStateException("client is required");
      }
      if (bucketResolver == null) {
        throw new IllegalStateException("a bucket or bucket resolver is required");
      }
      return new GcsStorageDriver(this);
    }

    private static String nonempty(String value, String field) {
      Objects.requireNonNull(value, field);
      if (value.isEmpty()) {
        throw new IllegalArgumentException(field + " must not be empty");
      }
      return value;
    }
  }
}
