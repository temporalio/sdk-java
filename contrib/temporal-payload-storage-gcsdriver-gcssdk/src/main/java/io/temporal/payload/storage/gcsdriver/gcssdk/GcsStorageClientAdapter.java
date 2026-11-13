package io.temporal.payload.storage.gcsdriver.gcssdk;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import io.temporal.common.Experimental;
import io.temporal.payload.storage.gcsdriver.GcsStorageDriverClient;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Google Cloud Storage adapter that runs blocking calls on a thread pool. Construct via {@link
 * #newBuilder()}.
 *
 * <p>The application configures credentials, transport, retries, and time limits on {@link
 * Storage}, and closes it after Temporal's storage work finishes. By default the adapter creates
 * its own thread pool, which {@link #close()} shuts down. An executor supplied with {@link
 * Builder#setExecutor(Executor)} is owned and shut down by the application.
 *
 * <p>Provider failures propagate unchanged. The storage driver adds operation and location context.
 *
 * <p>Cancellation interrupts the executor task on a best-effort basis. Configure Google client time
 * limits to bound operations that do not respond to interruption. Cancellation cannot undo an
 * upload.
 */
@Experimental
public final class GcsStorageClientAdapter implements GcsStorageDriverClient, AutoCloseable {
  private static final int DEFAULT_THREAD_POOL_MAX_SIZE = 400;
  private static final long THREAD_KEEP_ALIVE_SECONDS = 60;
  private static final AtomicInteger POOL_THREAD_COUNTER = new AtomicInteger();

  private final Storage storage;
  private final Executor executor;
  // Null when the application supplied the executor.
  private final ThreadPoolExecutor ownedPool;

  private GcsStorageClientAdapter(
      Storage storage, Executor executor, ThreadPoolExecutor ownedPool) {
    this.storage = storage;
    this.executor = executor;
    this.ownedPool = ownedPool;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Shuts down the adapter's built-in thread pool, if it has one. Calls in progress finish, and
   * later calls fail. Does not close {@link Storage} or an application-supplied executor. Safe to
   * call more than once.
   */
  @Override
  public void close() {
    if (ownedPool != null) {
      ownedPool.shutdown();
    }
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
  public CompletableFuture<Boolean> objectExists(
      @Nonnull String bucket, @Nonnull String objectName) {
    // The client returns null for a missing object or bucket. A missing bucket then fails the
    // upload with a 404.
    return submit(
        () ->
            storage.get(
                    BlobId.of(bucket, objectName),
                    Storage.BlobGetOption.fields(Storage.BlobField.NAME))
                != null);
  }

  @Nonnull
  @Override
  public CompletableFuture<byte[]> getObject(@Nonnull String bucket, @Nonnull String objectName) {
    return submit(() -> storage.readAllBytes(BlobId.of(bucket, objectName)));
  }

  /** Reports the Google client's project, when one is configured. */
  @Nonnull
  @Override
  public Map<String, String> describe() {
    StorageOptions options = storage.getOptions();
    String projectId = options == null ? null : options.getProjectId();
    if (projectId == null || projectId.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.singletonMap("client_project_id", projectId);
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
    } catch (Throwable e) {
      // Includes rejection after close() and errors such as failing to start a pool thread.
      result.completeExceptionally(e);
      task.cancel(true);
    }
    return result;
  }

  @Experimental
  public static final class Builder {
    private Storage storage;
    private Executor executor;
    private Integer threadPoolMaxSize;

    private Builder() {}

    /** Required. The Google Cloud Storage client used for all calls. */
    public Builder setStorage(@Nonnull Storage storage) {
      this.storage = Objects.requireNonNull(storage, "storage");
      return this;
    }

    /**
     * Maximum number of threads in the adapter's built-in thread pool. Defaults to 400, matching
     * the worker's default 200 workflow task slots and 200 activity slots. Threads start on demand
     * and stop after a minute without work. Calls beyond the limit wait in a queue. Cannot be
     * combined with {@link #setExecutor(Executor)}.
     */
    public Builder setThreadPoolMaxSize(int threadPoolMaxSize) {
      if (threadPoolMaxSize <= 0) {
        throw new IllegalArgumentException(
            "threadPoolMaxSize must be positive, got " + threadPoolMaxSize);
      }
      this.threadPoolMaxSize = threadPoolMaxSize;
      return this;
    }

    /**
     * Runs calls on an application-supplied executor instead of the built-in thread pool. The
     * application owns the executor and shuts it down; {@link GcsStorageClientAdapter#close()}
     * leaves it running. Avoid executors that reject tasks or run them on the calling thread.
     * Cannot be combined with {@link #setThreadPoolMaxSize(int)}.
     */
    public Builder setExecutor(@Nonnull Executor executor) {
      this.executor = Objects.requireNonNull(executor, "executor");
      return this;
    }

    public GcsStorageClientAdapter build() {
      if (storage == null) {
        throw new IllegalStateException("storage is required");
      }
      if (executor != null && threadPoolMaxSize != null) {
        throw new IllegalStateException(
            "set either an executor or a thread pool max size, not both");
      }
      if (executor != null) {
        return new GcsStorageClientAdapter(storage, executor, null);
      }
      ThreadPoolExecutor pool =
          newThreadPool(
              threadPoolMaxSize != null ? threadPoolMaxSize : DEFAULT_THREAD_POOL_MAX_SIZE);
      return new GcsStorageClientAdapter(storage, pool, pool);
    }

    private static ThreadPoolExecutor newThreadPool(int maxSize) {
      // Core and maximum sizes match because the pool only grows past its core size when the
      // queue is full, and this queue is unbounded. Core threads time out when idle.
      ThreadPoolExecutor pool =
          new ThreadPoolExecutor(
              maxSize,
              maxSize,
              THREAD_KEEP_ALIVE_SECONDS,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(),
              runnable -> {
                Thread thread =
                    new Thread(
                        runnable, "temporal-gcs-storage-" + POOL_THREAD_COUNTER.incrementAndGet());
                // Daemon threads do not keep the JVM alive if the adapter is never closed.
                thread.setDaemon(true);
                return thread;
              });
      pool.allowCoreThreadTimeOut(true);
      return pool;
    }
  }
}
