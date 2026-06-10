package io.temporal.payload.storage.s3;

import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * {@link S3Client} backed by the AWS SDK for Java v2 {@link S3AsyncClient}. The wrapped client must
 * be configured with credentials and a region by the caller.
 */
@Experimental
public final class S3AsyncClientAdapter implements S3Client {
  private final S3AsyncClient client;

  public S3AsyncClientAdapter(@Nonnull S3AsyncClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Nonnull
  @Override
  public CompletableFuture<Void> putObject(
      @Nonnull String bucket, @Nonnull String key, @Nonnull byte[] data) {
    // fromBytesUnsafe avoids a defensive copy of data; the driver never mutates it after this call.
    return client
        .putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            AsyncRequestBody.fromBytesUnsafe(data))
        .thenApply(response -> (Void) null);
  }

  @Nonnull
  @Override
  public CompletableFuture<Boolean> objectExists(@Nonnull String bucket, @Nonnull String key) {
    return client
        .headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
        .handle(
            (response, ex) -> {
              if (ex == null) {
                return true;
              }
              Throwable cause =
                  (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
              if (cause instanceof NoSuchKeyException) {
                return false;
              }
              if (cause instanceof S3Exception && ((S3Exception) cause).statusCode() == 404) {
                return false;
              }
              if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
              }
              throw new RuntimeException(cause);
            });
  }

  @Nonnull
  @Override
  public CompletableFuture<byte[]> getObject(@Nonnull String bucket, @Nonnull String key) {
    return client
        .getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).build(),
            AsyncResponseTransformer.toBytes())
        // asByteArrayUnsafe avoids a copy; the driver only reads the bytes (hash + parse).
        .thenApply(response -> response.asByteArrayUnsafe());
  }

  @Nonnull
  @Override
  public Map<String, String> describe() {
    Region region = client.serviceClientConfiguration().region();
    if (region == null) {
      return Collections.emptyMap();
    }
    return Collections.singletonMap("client_region", region.id());
  }
}
