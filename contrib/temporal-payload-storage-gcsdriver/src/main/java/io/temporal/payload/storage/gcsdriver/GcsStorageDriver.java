package io.temporal.payload.storage.gcsdriver;

import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.internal.payload.storage.CompletableFutures;
import io.temporal.internal.payload.storage.PayloadHasher;
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
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Stores complete serialized Temporal payloads in a flat namespace GCS bucket using conditional
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

  private final GcsStorageDriverClient client;
  private final String bucket;
  private final String name;
  private final int maxPayloadSize;

  private GcsStorageDriver(Builder builder) {
    client = builder.client;
    bucket = builder.bucket;
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
        byte[] data = payload.toByteArray();
        String hash = PayloadHasher.sha256Hex(data);
        String objectName = GcsStorageKey.forPayload(context.getTarget(), hash);
        prepared.add(new PreparedPayload(data, claimFor(bucket, objectName, hash)));
      }
    } catch (RuntimeException e) {
      return CompletableFutures.failedFuture(e);
    }
    return CompletableFutures.mapAsync(
        prepared,
        context.getCancellationToken(),
        payload -> {
          Map<String, String> claim = payload.claim.getClaimData();
          String objectName = claim.get("object_name");
          return request(
              () -> client.putObjectIfAbsent(bucket, objectName, payload.data),
              "upload failed " + location(bucket, objectName),
              ignored -> payload.claim);
        });
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
        String objectName = data.get("object_name");
        String legacyName = data.get("key");
        if (objectName != null && legacyName != null && !objectName.equals(legacyName)) {
          throw new GcsStorageException(
              "claim has conflicting object_name and key " + location(claimBucket, objectName));
        }
        if (objectName == null) {
          data.put("object_name", legacyName);
        }
        objectName = requiredField(data, "object_name");
        String algorithm = requiredField(data, "hash_algorithm");
        if (!HASH_ALGORITHM.equals(algorithm)) {
          throw new GcsStorageException("unsupported hash algorithm \"" + algorithm + "\"");
        }
        String hash = requiredField(data, "hash_value");
        if (!hash.matches("[0-9a-f]{64}")) {
          throw new GcsStorageException(
              "invalid SHA-256 hash_value " + location(claimBucket, objectName));
        }
        validated.add(data);
      }
    } catch (RuntimeException e) {
      return CompletableFutures.failedFuture(e);
    }
    return CompletableFutures.mapAsync(
        validated,
        context.getCancellationToken(),
        claim -> {
          String claimBucket = claim.get("bucket");
          String objectName = claim.get("object_name");
          return request(
              () -> client.getObject(claimBucket, objectName),
              "download failed " + location(claimBucket, objectName),
              data -> verifyAndParse(data, claim));
        });
  }

  private static StorageDriverClaim claimFor(String bucket, String objectName, String hash) {
    Map<String, String> data = new HashMap<>();
    data.put("bucket", bucket);
    data.put("object_name", objectName);
    data.put("key", objectName);
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
    return "[bucket=" + bucket + ", object_name=" + objectName + "]";
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
    final byte[] data;
    final StorageDriverClaim claim;

    PreparedPayload(byte[] data, StorageDriverClaim claim) {
      this.data = data;
      this.claim = claim;
    }
  }

  @Experimental
  public static final class Builder {
    private GcsStorageDriverClient client;
    private String bucket;
    private String name = DRIVER_TYPE;
    private int maxPayloadSize = DEFAULT_MAX_PAYLOAD_SIZE;

    private Builder() {}

    /** Required. The client used for storage operations. */
    public Builder setClient(@Nonnull GcsStorageDriverClient client) {
      this.client = Objects.requireNonNull(client, "client");
      return this;
    }

    /** Required. The nonempty, fixed upload bucket, configured with a flat namespace. */
    public Builder setBucket(@Nonnull String bucket) {
      this.bucket = nonempty(bucket, "bucket");
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
      if (bucket == null) {
        throw new IllegalStateException("bucket is required");
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
