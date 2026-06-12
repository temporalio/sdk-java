package io.temporal.payload.storage.s3;

import io.temporal.common.Experimental;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
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
    CompletableFuture<PutObjectResponse> request =
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            AsyncRequestBody.fromBytesUnsafe(data)); // avoids a defensive copy
    return abortRequestOnCancel(request, request.thenApply(response -> (Void) null));
  }

  @Nonnull
  @Override
  public CompletableFuture<Boolean> objectExists(@Nonnull String bucket, @Nonnull String key) {
    CompletableFuture<HeadObjectResponse> request =
        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    return abortRequestOnCancel(
        request,
        request.handle(
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
            }));
  }

  @Nonnull
  @Override
  public CompletableFuture<byte[]> getObject(@Nonnull String bucket, @Nonnull String key) {
    CompletableFuture<ResponseBytes<GetObjectResponse>> request =
        client.getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).build(),
            AsyncResponseTransformer.toBytes());
    return abortRequestOnCancel(request, request.thenApply(ResponseBytes::asByteArrayUnsafe));
  }

  /**
   * Returns {@code result}, wired so that cancelling it cancels the underlying {@code request}. The
   * AWS SDK aborts an async request when the future it returns is cancelled. Cancellation does not
   * otherwise propagate across the {@code thenApply}/{@code handle} boundary.
   */
  private static <T> CompletableFuture<T> abortRequestOnCancel(
      CompletableFuture<?> request, CompletableFuture<T> result) {
    result.whenComplete(
        (value, ex) -> {
          if (result.isCancelled()) {
            request.cancel(true);
          }
        });
    return result;
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
