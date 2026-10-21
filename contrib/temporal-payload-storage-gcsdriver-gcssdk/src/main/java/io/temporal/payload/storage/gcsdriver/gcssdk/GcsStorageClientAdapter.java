package io.temporal.payload.storage.gcsdriver.gcssdk;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import io.temporal.common.Experimental;
import io.temporal.payload.storage.gcsdriver.GcsStorageDriverClient;
import java.net.HttpURLConnection;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Google Cloud Storage adapter that runs blocking calls on an application-supplied executor.
 *
 * <p>The application configures credentials, transport, retries, and time limits on {@link
 * Storage}. It owns both resources and must shut them down after Temporal's storage work finishes.
 * Use a dedicated executor with bounded threads and queue capacity, and an abort rejection policy.
 *
 * <p>Provider failures propagate unchanged. The storage driver adds operation and location context.
 *
 * <p>Cancellation interrupts the executor task on a best-effort basis. Configure Google client time
 * limits to bound operations that do not respond to interruption. Cancellation cannot undo an
 * upload.
 */
@Experimental
public final class GcsStorageClientAdapter implements GcsStorageDriverClient {
  private final Storage storage;
  private final ExecutorService executor;

  public GcsStorageClientAdapter(@Nonnull Storage storage, @Nonnull ExecutorService executor) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Nonnull
  @Override
  public CompletableFuture<Void> putObjectIfAbsent(
      @Nonnull String bucket, @Nonnull String objectName, @Nonnull byte[] data) {
    return submit(
        () -> {
          try {
            storage.create(
                BlobInfo.newBuilder(bucket, objectName)
                    .setContentType("application/octet-stream")
                    .build(),
                data,
                Storage.BlobTargetOption.doesNotExist());
          } catch (StorageException e) {
            if (e.getCode() != HttpURLConnection.HTTP_PRECON_FAILED) {
              throw e;
            }
            // The doesNotExist() precondition failed because the object already exists.
          }
          return null;
        });
  }

  @Nonnull
  @Override
  public CompletableFuture<byte[]> getObject(@Nonnull String bucket, @Nonnull String objectName) {
    return submit(() -> storage.readAllBytes(BlobId.of(bucket, objectName)));
  }

  private <T> CompletableFuture<T> submit(Supplier<T> call) {
    CompletableFuture<T> result = new CompletableFuture<>();
    FutureTask<T> task =
        new FutureTask<T>(call::get) {
          @Override
          protected void done() {
            try {
              result.complete(get());
            } catch (CancellationException e) {
              result.cancel(true);
            } catch (ExecutionException e) {
              result.completeExceptionally(e.getCause());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              result.completeExceptionally(e);
            }
          }
        };
    result.whenComplete(
        (value, error) -> {
          if (result.isCancelled()) {
            task.cancel(true);
          }
        });
    try {
      // Wire cancellation before submission so even queued tasks can be cancelled safely.
      executor.execute(task);
    } catch (RejectedExecutionException e) {
      result.completeExceptionally(e);
      task.cancel(true);
    }
    return result;
  }
}
