package io.temporal.payload.storage.gcsdriver.gcssdk;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.gcsdriver.GcsStorageDriver;
import io.temporal.payload.storage.gcsdriver.GcsStorageException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class GcsStorageClientAdapterTest {
  private Storage storage;
  private ExecutorService executor;
  private GcsStorageClientAdapter adapter;

  @Before
  public void setUp() {
    storage = mock(Storage.class);
    executor = Executors.newSingleThreadExecutor();
    adapter = withExecutor(executor);
  }

  @After
  public void tearDown() throws InterruptedException {
    executor.shutdownNow();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  public void builderValidatesConfiguration() {
    assertThrows(
        NullPointerException.class, () -> GcsStorageClientAdapter.newBuilder().setStorage(null));
    assertThrows(
        NullPointerException.class, () -> GcsStorageClientAdapter.newBuilder().setExecutor(null));
    for (int size : new int[] {0, -1}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> GcsStorageClientAdapter.newBuilder().setThreadPoolMaxSize(size));
    }
    assertThrows(IllegalStateException.class, () -> GcsStorageClientAdapter.newBuilder().build());
    assertThrows(
        IllegalStateException.class,
        () ->
            GcsStorageClientAdapter.newBuilder()
                .setStorage(storage)
                .setExecutor(executor)
                .setThreadPoolMaxSize(4)
                .build());
    assertThrows(
        IllegalStateException.class,
        () ->
            GcsStorageClientAdapter.newBuilder()
                .setStorage(storage)
                .setThreadPoolMaxSize(4)
                .setExecutor(executor)
                .build());
  }

  @Test
  public void builtInPoolRunsCallsOnNamedDaemonThreads() throws Exception {
    List<Thread> threads = new CopyOnWriteArrayList<>();
    when(storage.readAllBytes(BlobId.of("bucket", "object")))
        .thenAnswer(
            invocation -> {
              threads.add(Thread.currentThread());
              return new byte[] {1};
            });
    try (GcsStorageClientAdapter pooled =
        GcsStorageClientAdapter.newBuilder().setStorage(storage).build()) {
      assertArrayEquals(
          new byte[] {1}, pooled.getObject("bucket", "object").get(5, TimeUnit.SECONDS));
    }
    assertEquals(1, threads.size());
    assertTrue(
        threads.get(0).getName(), threads.get(0).getName().startsWith("temporal-gcs-storage-"));
    assertTrue(threads.get(0).isDaemon());
  }

  @Test
  public void builtInPoolQueuesCallsBeyondMaxSize() throws Exception {
    AtomicInteger running = new AtomicInteger();
    CountDownLatch twoRunning = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    when(storage.readAllBytes(any(BlobId.class)))
        .thenAnswer(
            invocation -> {
              running.incrementAndGet();
              twoRunning.countDown();
              release.await();
              return new byte[] {1};
            });
    try (GcsStorageClientAdapter pooled =
        GcsStorageClientAdapter.newBuilder().setStorage(storage).setThreadPoolMaxSize(2).build()) {
      List<CompletableFuture<byte[]>> downloads = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        downloads.add(pooled.getObject("bucket", "object-" + i));
      }
      assertTrue(twoRunning.await(5, TimeUnit.SECONDS));
      Thread.sleep(200);
      assertEquals(2, running.get());
      assertFalse(downloads.get(2).isDone());
      release.countDown();
      for (CompletableFuture<byte[]> download : downloads) {
        assertArrayEquals(new byte[] {1}, download.get(5, TimeUnit.SECONDS));
      }
      assertEquals(3, running.get());
    }
  }

  @Test
  public void closeLetsRunningCallsFinishAndRejectsLaterCalls() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(storage.readAllBytes(BlobId.of("bucket", "object")))
        .thenAnswer(
            invocation -> {
              started.countDown();
              release.await();
              return new byte[] {1};
            });
    GcsStorageClientAdapter pooled =
        GcsStorageClientAdapter.newBuilder().setStorage(storage).build();
    CompletableFuture<byte[]> running = pooled.getObject("bucket", "object");
    assertTrue(started.await(5, TimeUnit.SECONDS));
    pooled.close();
    pooled.close();
    release.countDown();
    assertArrayEquals(new byte[] {1}, running.get(5, TimeUnit.SECONDS));
    assertTrue(failure(pooled.getObject("bucket", "object")) instanceof RejectedExecutionException);
    verify(storage, never()).close();
  }

  @Test
  public void closeLeavesApplicationExecutorRunning() throws Exception {
    when(storage.readAllBytes(BlobId.of("bucket", "object"))).thenReturn(new byte[] {1});
    adapter.close();
    assertFalse(executor.isShutdown());
    assertArrayEquals(
        new byte[] {1}, adapter.getObject("bucket", "object").get(5, TimeUnit.SECONDS));
  }

  @Test
  public void createsOnlyWhenAbsentWithBinaryContentType() throws Exception {
    byte[] data = {1, 2, 3};
    adapter.putObjectIfAbsent("bucket", "日本語/object", data).get(5, TimeUnit.SECONDS);
    verify(storage)
        .create(
            eq(
                BlobInfo.newBuilder("bucket", "日本語/object")
                    .setContentType("application/octet-stream")
                    .build()),
            same(data),
            eq(Storage.BlobTargetOption.doesNotExist()));
    verifyNoMoreInteractions(storage);
  }

  @Test
  public void conditionalCreate412IsSuccessfulDeduplication() throws Exception {
    when(storage.create(
            any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class)))
        .thenThrow(new StorageException(412, "already exists"));
    assertNull(
        adapter.putObjectIfAbsent("bucket", "object", new byte[] {1}).get(5, TimeUnit.SECONDS));
  }

  @Test
  public void ordinaryCreateErrorsPropagateUnchanged() throws Exception {
    for (int code : new int[] {403, 404, 429, 500}) {
      StorageException original = new StorageException(code, "upload failure");
      when(storage.create(
              any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class)))
          .thenThrow(original);
      assertSame(original, failure(adapter.putObjectIfAbsent("bucket", "object", new byte[] {1})));
    }
  }

  @Test
  public void existenceCheckRequestsOnlyTheObjectName() throws Exception {
    BlobId id = BlobId.of("bucket", "日本語/object");
    Storage.BlobGetOption nameOnly = Storage.BlobGetOption.fields(Storage.BlobField.NAME);
    when(storage.get(eq(id), any(Storage.BlobGetOption[].class))).thenReturn(mock(Blob.class));
    assertTrue(adapter.objectExists("bucket", "日本語/object").get(5, TimeUnit.SECONDS));
    verify(storage).get(eq(id), eq(nameOnly));
    verifyNoMoreInteractions(storage);
  }

  @Test
  public void missingObjectIsReportedAsAbsent() throws Exception {
    assertFalse(adapter.objectExists("bucket", "object").get(5, TimeUnit.SECONDS));
  }

  @Test
  public void existenceCheckErrorsPropagateUnchanged() throws Exception {
    for (int code : new int[] {403, 429, 500}) {
      StorageException original = new StorageException(code, "metadata failure");
      when(storage.get(any(BlobId.class), any(Storage.BlobGetOption[].class))).thenThrow(original);
      assertSame(original, failure(adapter.objectExists("bucket", "object")));
    }
  }

  @Test
  public void driverSkipsUploadWhenObjectExists() throws Exception {
    when(storage.get(any(BlobId.class), any(Storage.BlobGetOption[].class)))
        .thenReturn(mock(Blob.class));
    GcsStorageDriver driver =
        GcsStorageDriver.newBuilder().setClient(adapter).setBucket("bucket").build();
    driver
        .store(() -> null, Collections.singletonList(Payload.getDefaultInstance()))
        .get(5, TimeUnit.SECONDS);
    verify(storage, never())
        .create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class));
  }

  @Test
  public void describesConfiguredProject() {
    StorageOptions options = mock(StorageOptions.class);
    when(storage.getOptions()).thenReturn(options);
    when(options.getProjectId()).thenReturn("my-project");
    assertEquals(Collections.singletonMap("client_project_id", "my-project"), adapter.describe());
    when(options.getProjectId()).thenReturn(null);
    assertEquals(Collections.emptyMap(), adapter.describe());
  }

  @Test
  public void downloadsBytesUsingBlobId() throws Exception {
    byte[] data = {3, 2, 1};
    when(storage.readAllBytes(BlobId.of("bucket", "object"))).thenReturn(data);
    assertArrayEquals(data, adapter.getObject("bucket", "object").get(5, TimeUnit.SECONDS));
    verify(storage).readAllBytes(BlobId.of("bucket", "object"));
  }

  @Test
  public void missingPermissionAnd412ReadErrorsAreNotDeduplication() throws Exception {
    for (int code : new int[] {404, 403, 412}) {
      StorageException original = new StorageException(code, "read failure");
      doThrow(original).when(storage).readAllBytes(BlobId.of("bucket", "object"));
      assertSame(original, failure(adapter.getObject("bucket", "object")));
    }
  }

  @Test
  public void cancellationInterruptsRunningGoogleCall() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    when(storage.readAllBytes(BlobId.of("bucket", "object")))
        .thenAnswer(
            invocation -> {
              started.countDown();
              try {
                new CountDownLatch(1).await();
                throw new AssertionError("expected interruption");
              } catch (InterruptedException e) {
                interrupted.countDown();
                throw new StorageException(499, "interrupted", e);
              }
            });
    CompletableFuture<byte[]> download = adapter.getObject("bucket", "object");
    assertTrue(started.await(5, TimeUnit.SECONDS));
    assertTrue(download.cancel(true));
    assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    assertTrue(download.isCancelled());
  }

  @Test
  public void queuedTaskIsCancelledBeforeItCanRun() {
    ExecutorService queued = mock(ExecutorService.class);
    CompletableFuture<byte[]> download = withExecutor(queued).getObject("bucket", "object");
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(queued).execute(task.capture());
    assertFalse(download.isDone());
    download.cancel(true);
    assertTrue(((Future<?>) task.getValue()).isCancelled());
    task.getValue().run();
    verifyNoInteractions(storage);
  }

  @Test
  public void executorRejectionCompletesFutureExceptionally() throws Exception {
    ExecutorService rejected = mock(ExecutorService.class);
    RejectedExecutionException original = new RejectedExecutionException("queue is full");
    doThrow(original).when(rejected).execute(any(Runnable.class));
    assertSame(original, failure(withExecutor(rejected).getObject("bucket", "object")));
    verifyNoInteractions(storage);
  }

  @Test
  public void queuedCallCanReuseSubmittingExecutorThread() throws Exception {
    byte[] data = {1, 2, 3};
    when(storage.readAllBytes(BlobId.of("bucket", "object"))).thenReturn(data);
    CompletableFuture<byte[]> download =
        executor.submit(() -> adapter.getObject("bucket", "object")).get(5, TimeUnit.SECONDS);
    assertArrayEquals(data, download.get(5, TimeUnit.SECONDS));
    verify(storage).readAllBytes(BlobId.of("bucket", "object"));
  }

  @Test
  public void driverRoundTripWithGoogleAndTemporalDependenciesTogether() throws Exception {
    Payload payload =
        Payload.newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("binary/plain"))
            .setData(ByteString.copyFromUtf8("hello"))
            .build();
    when(storage.readAllBytes(any(BlobId.class), any(Storage.BlobSourceOption[].class)))
        .thenReturn(payload.toByteArray());
    GcsStorageDriver driver =
        GcsStorageDriver.newBuilder().setClient(adapter).setBucket("bucket").build();
    List<StorageDriverClaim> claims =
        driver.store(() -> null, Collections.singletonList(payload)).get(5, TimeUnit.SECONDS);
    assertEquals(
        Collections.singletonList(payload),
        driver.retrieve(new StorageDriverRetrieveContext() {}, claims).get(5, TimeUnit.SECONDS));
    assertFalse(executor.isShutdown());
    verify(storage, never()).close();
  }

  @Test
  public void driverAddsContextAndKeepsOriginalProviderFailure() throws Exception {
    StorageException original = new StorageException(403, "access denied");
    when(storage.create(
            any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class)))
        .thenThrow(original);
    GcsStorageDriver driver =
        GcsStorageDriver.newBuilder().setClient(adapter).setBucket("bucket").build();
    Throwable error =
        failure(driver.store(() -> null, Collections.singletonList(Payload.getDefaultInstance())));
    assertTrue(String.valueOf(error), error instanceof GcsStorageException);
    assertSame(original, error.getCause());
    assertTrue(
        error.getMessage(),
        error.getMessage().startsWith("upload failed [bucket=bucket, object_name="));
  }

  private GcsStorageClientAdapter withExecutor(Executor executor) {
    return GcsStorageClientAdapter.newBuilder().setStorage(storage).setExecutor(executor).build();
  }

  private static Throwable failure(CompletableFuture<?> future) throws Exception {
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    return error.getCause();
  }
}
