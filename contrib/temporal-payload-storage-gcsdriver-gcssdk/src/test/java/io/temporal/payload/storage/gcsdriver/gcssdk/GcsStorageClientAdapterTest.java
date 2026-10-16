package io.temporal.payload.storage.gcsdriver.gcssdk;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.gcsdriver.GcsStorageDriver;
import io.temporal.payload.storage.gcsdriver.GcsStorageException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
    adapter = new GcsStorageClientAdapter(storage, executor);
  }

  @After
  public void tearDown() throws InterruptedException {
    executor.shutdownNow();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
  }

  @Test
  public void requiresApplicationResources() {
    assertThrows(NullPointerException.class, () -> new GcsStorageClientAdapter(null, executor));
    assertThrows(NullPointerException.class, () -> new GcsStorageClientAdapter(storage, null));
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
  public void ordinaryCreateErrorsPropagateWithOriginalCauseAndLocation() throws Exception {
    for (int code : new int[] {403, 404, 429, 500}) {
      StorageException original = new StorageException(code, "upload failure");
      when(storage.create(
              any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption[].class)))
          .thenThrow(original);
      GcsStorageException error =
          failure(adapter.putObjectIfAbsent("bucket", "object", new byte[] {1}));
      assertSame(original, error.getCause());
      assertTrue(
          error.getMessage(),
          error.getMessage().contains("upload failed [bucket=bucket, object_name=object]"));
    }
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
      GcsStorageException error = failure(adapter.getObject("bucket", "object"));
      assertSame(original, error.getCause());
      assertTrue(
          error.getMessage(),
          error.getMessage().contains("download failed [bucket=bucket, object_name=object]"));
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
    CompletableFuture<byte[]> download =
        new GcsStorageClientAdapter(storage, queued).getObject("bucket", "object");
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
    GcsStorageException error =
        failure(new GcsStorageClientAdapter(storage, rejected).getObject("bucket", "object"));
    assertSame(original, error.getCause());
    verifyNoInteractions(storage);
  }

  @Test
  public void callerRunsExecutorCannotPerformBlockingIoOnCaller() throws Exception {
    ExecutorService callerRuns = mock(ExecutorService.class);
    doAnswer(
            invocation -> {
              invocation.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(callerRuns)
        .execute(any(Runnable.class));
    GcsStorageException error =
        failure(new GcsStorageClientAdapter(storage, callerRuns).getObject("bucket", "object"));
    assertTrue(error.getCause() instanceof RejectedExecutionException);
    verifyNoInteractions(storage);
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

  private static GcsStorageException failure(CompletableFuture<?> future) throws Exception {
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
    assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof GcsStorageException);
    return (GcsStorageException) error.getCause();
  }
}
