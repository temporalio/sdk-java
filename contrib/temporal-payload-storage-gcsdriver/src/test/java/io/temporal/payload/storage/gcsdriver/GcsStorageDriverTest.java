package io.temporal.payload.storage.gcsdriver;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.CancellationToken;
import io.temporal.internal.payload.storage.CompletableFutures;
import io.temporal.internal.payload.storage.PayloadHasher;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class GcsStorageDriverTest {
  private static final StorageDriverStoreContext STORE_CONTEXT = () -> null;
  private static final StorageDriverRetrieveContext RETRIEVE_CONTEXT =
      new StorageDriverRetrieveContext() {};
  private static final String HELLO_HASH =
      "02c64c4d9ab7bb8b5007525541131a945744d299fe58b5f02ae02a3fd659fdf1";

  @Test
  public void defaultsAndCustomName() {
    assertEquals("gcp.gcsdriver", driver(new InMemoryClient()).getName());
    assertEquals("gcp.gcsdriver", driver(new InMemoryClient()).getType());
    GcsStorageDriver custom = builder(new InMemoryClient()).setName("archive").build();
    assertEquals("archive", custom.getName());
    assertEquals("gcp.gcsdriver", custom.getType());
  }

  @Test
  public void requiresClientAndBucket() {
    assertThrows(
        IllegalStateException.class, () -> GcsStorageDriver.newBuilder().setBucket("b").build());
    assertThrows(
        IllegalStateException.class,
        () -> GcsStorageDriver.newBuilder().setClient(new InMemoryClient()).build());
  }

  @Test
  public void rejectsNullConfiguration() {
    assertThrows(NullPointerException.class, () -> GcsStorageDriver.newBuilder().setClient(null));
    assertThrows(NullPointerException.class, () -> GcsStorageDriver.newBuilder().setBucket(null));
    assertThrows(NullPointerException.class, () -> GcsStorageDriver.newBuilder().setName(null));
  }

  @Test
  public void rejectsEmptyBucketAndName() {
    assertThrows(IllegalArgumentException.class, () -> GcsStorageDriver.newBuilder().setBucket(""));
    assertThrows(IllegalArgumentException.class, () -> GcsStorageDriver.newBuilder().setName(""));
  }

  @Test
  public void requiresPositiveUploadLimit() {
    for (int limit : new int[] {0, -1}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> GcsStorageDriver.newBuilder().setMaxPayloadSize(limit));
    }
  }

  @Test
  public void storesCompletePayloadAndCanonicalClaim() {
    InMemoryClient client = new InMemoryClient();
    Payload payload = Payload.newBuilder().setData(ByteString.copyFromUtf8("hello")).build();
    StorageDriverClaim claim = store(driver(client), payload).get(0);
    assertEquals(canonicalClaim().getClaimData(), claim.getClaimData());
    assertArrayEquals(
        payload.toByteArray(), client.objects.get("test-bucket/" + objectName(claim)));
    assertEquals(1, client.putAttempts.get());
  }

  @Test
  public void roundTripsDataAndAllMetadata() {
    GcsStorageDriver driver = driver(new InMemoryClient());
    Payload original =
        payload("日本語 data").toBuilder()
            .putMetadata("custom", ByteString.copyFrom(new byte[] {0, 1, -1}))
            .build();
    assertEquals(
        Collections.singletonList(original),
        driver.retrieve(RETRIEVE_CONTEXT, store(driver, original)).join());
  }

  @Test
  public void emptyBatchesMakeNoRequests() {
    InMemoryClient client = new InMemoryClient();
    GcsStorageDriver driver = driver(client);
    assertEquals(
        Collections.emptyList(), driver.store(STORE_CONTEXT, Collections.emptyList()).join());
    assertEquals(
        Collections.emptyList(), driver.retrieve(RETRIEVE_CONTEXT, Collections.emptyList()).join());
    assertEquals(0, client.putAttempts.get());
    assertEquals(0, client.getAttempts.get());
  }

  @Test
  public void identicalBytesProduceSameClaimsOnRepeatedUploads() {
    InMemoryClient client = new InMemoryClient();
    GcsStorageDriver driver = driver(client);
    Payload payload = payload("duplicate");
    List<StorageDriverClaim> first = store(driver, payload);
    assertEquals(first, store(driver, payload));
    assertEquals(2, client.putAttempts.get());
  }

  @Test
  public void validatesSerializedSizeIncludingMetadataBeforeAnyUpload() {
    InMemoryClient client = new InMemoryClient();
    Payload small = payload("small");
    Payload large =
        small.toBuilder().putMetadata("extra", ByteString.copyFromUtf8("large metadata")).build();
    GcsStorageDriver driver = builder(client).setMaxPayloadSize(small.getSerializedSize()).build();
    assertTrue(
        failure(driver.store(STORE_CONTEXT, Arrays.asList(small, large)))
            .getMessage()
            .contains("exceeds maximum " + small.getSerializedSize()));
    assertEquals(0, client.putAttempts.get());
    assertEquals(1, store(driver, small).size());
  }

  @Test
  public void rejectsUnusableNamespaceBeforeUpload() {
    InMemoryClient client = new InMemoryClient();
    String namespace = String.join("", Collections.nCopies(400, "日"));
    StorageDriverStoreContext context =
        () -> new StorageDriverWorkflowInfo(namespace, "id", "run", "type");
    assertTrue(
        failure(driver(client).store(context, Collections.singletonList(payload("x"))))
            .getMessage()
            .contains("1024 UTF-8 bytes"));
    assertEquals(0, client.putAttempts.get());
  }

  @Test
  public void identicalPayloadsInDifferentTargetsHaveDifferentNames() {
    GcsStorageDriver driver = driver(new InMemoryClient());
    Payload payload = payload("same");
    String workflowName =
        objectName(
            driver
                .store(
                    () -> new StorageDriverWorkflowInfo("ns", "id", "run", "wf"),
                    Collections.singletonList(payload))
                .join()
                .get(0));
    String activityName =
        objectName(
            driver
                .store(
                    () -> new StorageDriverActivityInfo("ns", "id", "run", "act"),
                    Collections.singletonList(payload))
                .join()
                .get(0));
    assertTrue(workflowName.startsWith("v0/ns/ns/wt/wf/wi/id/ri/run/"));
    assertTrue(activityName.startsWith("v0/ns/ns/at/act/ai/id/ri/run/"));
    assertNotEquals(workflowName, activityName);
  }

  @Test
  public void outOfOrderUploadsPreserveClaimOrder() {
    ControlledClient client = new ControlledClient();
    List<Payload> inputs = Arrays.asList(payload("a"), payload("b"), payload("c"));
    CompletableFuture<List<StorageDriverClaim>> batch = driver(client).store(STORE_CONTEXT, inputs);
    client.uploads.get(2).complete(null);
    client.uploads.get(0).complete(null);
    assertFalse(batch.isDone());
    client.uploads.get(1).complete(null);
    for (int i = 0; i < inputs.size(); i++) {
      assertEquals(
          PayloadHasher.sha256Hex(inputs.get(i).toByteArray()),
          batch.join().get(i).getClaimData().get("hash_value"));
    }
  }

  @Test
  public void outOfOrderDownloadsPreservePayloadOrder() {
    List<Payload> inputs = Arrays.asList(payload("a"), payload("b"), payload("c"));
    List<StorageDriverClaim> claims =
        driver(new InMemoryClient()).store(STORE_CONTEXT, inputs).join();
    ControlledClient client = new ControlledClient();
    CompletableFuture<List<Payload>> batch = driver(client).retrieve(RETRIEVE_CONTEXT, claims);
    client.downloads.get(2).complete(inputs.get(2).toByteArray());
    client.downloads.get(1).complete(inputs.get(1).toByteArray());
    assertFalse(batch.isDone());
    client.downloads.get(0).complete(inputs.get(0).toByteArray());
    assertEquals(inputs, batch.join());
  }

  @Test
  public void canonicalAndLegacyClaimsReadOriginalBucketAndIgnoreUnknownFields() {
    InMemoryClient client = new InMemoryClient();
    Payload hello = Payload.newBuilder().setData(ByteString.copyFromUtf8("hello")).build();
    client.objects.put("test-bucket/v0/d/sha256/" + HELLO_HASH, hello.toByteArray());
    GcsStorageDriver driver = builder(client).setBucket("new-upload-bucket").build();
    for (String removed : new String[] {"key", "object_name", "neither"}) {
      Map<String, String> data = new HashMap<>(canonicalClaim().getClaimData());
      data.remove(removed);
      data.put("future_field", "ignored");
      assertEquals(
          Collections.singletonList(hello),
          driver
              .retrieve(RETRIEVE_CONTEXT, Collections.singletonList(new StorageDriverClaim(data)))
              .join());
    }
  }

  @Test
  public void validatesEveryRequiredClaimFieldBeforeDownloading() {
    InMemoryClient client = new InMemoryClient();
    for (String field : new String[] {"bucket", "object_name", "hash_algorithm", "hash_value"}) {
      for (String invalid : new String[] {null, ""}) {
        Map<String, String> data = new HashMap<>(canonicalClaim().getClaimData());
        data.remove("key");
        if (invalid == null) {
          data.remove(field);
        } else {
          data.put(field, invalid);
        }
        GcsStorageException error =
            failure(
                driver(client)
                    .retrieve(
                        RETRIEVE_CONTEXT,
                        Arrays.asList(canonicalClaim(), new StorageDriverClaim(data))));
        assertTrue(error.getMessage(), error.getMessage().contains(field));
      }
    }
    assertEquals(0, client.getAttempts.get());
  }

  @Test
  public void rejectsConflictingObjectNames() {
    InMemoryClient client = new InMemoryClient();
    Map<String, String> data = new HashMap<>(canonicalClaim().getClaimData());
    data.put("key", "another-object");
    assertTrue(
        failure(
                driver(client)
                    .retrieve(
                        RETRIEVE_CONTEXT, Collections.singletonList(new StorageDriverClaim(data))))
            .getMessage()
            .contains("conflicting"));
    assertEquals(0, client.getAttempts.get());
  }

  @Test
  public void rejectsUnsupportedAlgorithmAndMalformedHashBeforeDownloading() {
    InMemoryClient client = new InMemoryClient();
    for (String[] entry :
        new String[][] {
          {"hash_algorithm", "md5"},
          {"hash_value", "xyz"},
          {"hash_value", HELLO_HASH.toUpperCase(java.util.Locale.ROOT)}
        }) {
      Map<String, String> data = new HashMap<>(canonicalClaim().getClaimData());
      data.put(entry[0], entry[1]);
      failure(
          driver(client)
              .retrieve(
                  RETRIEVE_CONTEXT, Arrays.asList(canonicalClaim(), new StorageDriverClaim(data))));
    }
    assertEquals(0, client.getAttempts.get());
  }

  @Test
  public void corruptedBytesFailIntegrityCheckBeforeProtobufParsing() {
    InMemoryClient client = new InMemoryClient();
    client.objects.put("test-bucket/" + objectName(canonicalClaim()), new byte[] {(byte) 0xff});
    GcsStorageException error =
        failure(
            driver(client).retrieve(RETRIEVE_CONTEXT, Collections.singletonList(canonicalClaim())));
    assertTrue(
        error.getMessage(),
        error
            .getMessage()
            .startsWith("integrity check failed [bucket=test-bucket, object_name=v0/d/sha256/"));
    assertNull(error.getCause());
  }

  @Test
  public void invalidProtobufWithValidChecksumFailsParsing() {
    InMemoryClient client = new InMemoryClient();
    byte[] invalid = {(byte) 0xff};
    client.objects.put("test-bucket/invalid", invalid);
    Map<String, String> data = new HashMap<>(canonicalClaim().getClaimData());
    data.put("object_name", "invalid");
    data.put("key", "invalid");
    data.put("hash_value", PayloadHasher.sha256Hex(invalid));
    GcsStorageException error =
        failure(
            driver(client)
                .retrieve(
                    RETRIEVE_CONTEXT, Collections.singletonList(new StorageDriverClaim(data))));
    assertTrue(error.getMessage(), error.getMessage().contains("unmarshal payload"));
    assertTrue(error.getCause() instanceof InvalidProtocolBufferException);
  }

  @Test
  public void missingObjectIsAnErrorWithLocation() {
    GcsStorageException error =
        failure(
            driver(new InMemoryClient())
                .retrieve(RETRIEVE_CONTEXT, Collections.singletonList(canonicalClaim())));
    assertTrue(
        error.getMessage(),
        error.getMessage().contains("download failed [bucket=test-bucket, object_name="));
    assertEquals("not found", error.getCause().getMessage());
  }

  @Test
  public void providerFailuresKeepContextAndOriginalCause() {
    for (boolean synchronous : new boolean[] {false, true}) {
      RuntimeException cause = new RuntimeException("access denied");
      InMemoryClient client =
          new InMemoryClient() {
            @Override
            public CompletableFuture<Void> putObjectIfAbsent(
                String bucket, String name, byte[] bytes) {
              if (synchronous) {
                throw cause;
              }
              return CompletableFutures.failedFuture(new CompletionException(cause));
            }

            @Override
            public CompletableFuture<byte[]> getObject(String bucket, String name) {
              if (synchronous) {
                throw cause;
              }
              return CompletableFutures.failedFuture(new CompletionException(cause));
            }
          };
      GcsStorageException upload =
          failure(driver(client).store(STORE_CONTEXT, Collections.singletonList(payload("x"))));
      GcsStorageException download =
          failure(
              driver(client)
                  .retrieve(RETRIEVE_CONTEXT, Collections.singletonList(canonicalClaim())));
      assertTrue(upload.getMessage().contains("upload failed [bucket=test-bucket, object_name="));
      assertTrue(
          download.getMessage().contains("download failed [bucket=test-bucket, object_name="));
      assertSame(cause, upload.getCause());
      assertSame(cause, download.getCause());
    }
  }

  @Test
  public void uploadFailureCancelsPendingUploads() {
    ControlledClient client = new ControlledClient();
    CompletableFuture<?> batch =
        driver(client).store(STORE_CONTEXT, Arrays.asList(payload("a"), payload("b")));
    RuntimeException cause = new RuntimeException("boom");
    client.uploads.get(1).completeExceptionally(cause);
    assertSame(cause, failure(batch).getCause());
    assertTrue(client.uploads.get(0).isCancelled());
  }

  @Test
  public void downloadFailureCancelsPendingDownloads() {
    ControlledClient client = new ControlledClient();
    CompletableFuture<?> batch =
        driver(client)
            .retrieve(RETRIEVE_CONTEXT, Arrays.asList(canonicalClaim(), canonicalClaim()));
    RuntimeException cause = new RuntimeException("boom");
    client.downloads.get(0).completeExceptionally(cause);
    assertSame(cause, failure(batch).getCause());
    assertTrue(client.downloads.get(1).isCancelled());
  }

  @Test
  public void synchronousFailureStopsLaunchingRequests() {
    InMemoryClient client = new InMemoryClient();
    client.putError = new RuntimeException("boom");
    failure(driver(client).store(STORE_CONTEXT, Arrays.asList(payload("a"), payload("b"))));
    assertEquals(1, client.putAttempts.get());
  }

  @Test
  public void cancellingBatchFutureCancelsProviderFutures() {
    ControlledClient client = new ControlledClient();
    CompletableFuture<?> upload =
        driver(client).store(STORE_CONTEXT, Collections.singletonList(payload("a")));
    CompletableFuture<?> download =
        driver(client).retrieve(RETRIEVE_CONTEXT, Collections.singletonList(canonicalClaim()));
    upload.cancel(true);
    download.cancel(true);
    assertTrue(client.uploads.get(0).isCancelled());
    assertTrue(client.downloads.get(0).isCancelled());
  }

  @Test
  public void contextCancellationCancelsOperationsAndClosesRegistrations() {
    ControlledClient client = new ControlledClient();
    TestToken token = new TestToken();
    CompletableFuture<?> upload =
        driver(client).store(storeContext(token), Collections.singletonList(payload("a")));
    assertNotNull(token.callback);
    token.cancel();
    assertTrue(upload.isCancelled());
    assertTrue(client.uploads.get(0).isCancelled());
    assertNull(token.callback);
    TestToken retrieveToken = new TestToken();
    CompletableFuture<?> download =
        driver(client)
            .retrieve(retrieveContext(retrieveToken), Collections.singletonList(canonicalClaim()));
    retrieveToken.cancel();
    assertTrue(download.isCancelled());
    assertTrue(client.downloads.get(0).isCancelled());
    assertNull(retrieveToken.callback);
  }

  @Test
  public void alreadyCancelledContextStartsNoWork() {
    TestToken token = new TestToken();
    token.cancel();
    InMemoryClient client = new InMemoryClient();
    assertTrue(
        driver(client)
            .store(storeContext(token), Collections.singletonList(payload("x")))
            .isCancelled());
    assertTrue(
        driver(client)
            .retrieve(retrieveContext(token), Collections.singletonList(canonicalClaim()))
            .isCancelled());
    assertEquals(0, client.putAttempts.get());
    assertEquals(0, client.getAttempts.get());
    assertNull(token.callback);
  }

  @Test
  public void registrationsCloseOnSuccessAndFailure() {
    TestToken token = new TestToken();
    InMemoryClient client = new InMemoryClient();
    driver(client).store(storeContext(token), Collections.singletonList(payload("x"))).join();
    assertNull(token.callback);
    failure(
        driver(client)
            .retrieve(retrieveContext(token), Collections.singletonList(canonicalClaim())));
    assertNull(token.callback);
  }

  @Test
  public void cancellationRacingWithClientSubmissionCancelsReturnedRequest() {
    TestToken token = new TestToken();
    CompletableFuture<Void> pending = new CompletableFuture<>();
    InMemoryClient client =
        new InMemoryClient() {
          @Override
          public CompletableFuture<Void> putObjectIfAbsent(
              String bucket, String name, byte[] data) {
            token.cancel();
            return pending;
          }
        };
    assertTrue(
        driver(client)
            .store(storeContext(token), Collections.singletonList(payload("x")))
            .isCancelled());
    assertTrue(pending.isCancelled());
    assertNull(token.callback);
  }

  private static Payload payload(String data) {
    return Payload.newBuilder()
        .setData(ByteString.copyFromUtf8(data))
        .putMetadata("encoding", ByteString.copyFromUtf8("binary/plain"))
        .build();
  }

  private static GcsStorageDriver.Builder builder(GcsStorageDriverClient client) {
    return GcsStorageDriver.newBuilder().setClient(client).setBucket("test-bucket");
  }

  private static GcsStorageDriver driver(GcsStorageDriverClient client) {
    return builder(client).build();
  }

  private static List<StorageDriverClaim> store(GcsStorageDriver driver, Payload payload) {
    return driver.store(STORE_CONTEXT, Collections.singletonList(payload)).join();
  }

  private static String objectName(StorageDriverClaim claim) {
    return claim.getClaimData().get("object_name");
  }

  private static StorageDriverClaim canonicalClaim() {
    Map<String, String> claim = new HashMap<>();
    claim.put("bucket", "test-bucket");
    claim.put("object_name", "v0/d/sha256/" + HELLO_HASH);
    claim.put("key", "v0/d/sha256/" + HELLO_HASH);
    claim.put("hash_algorithm", "sha256");
    claim.put("hash_value", HELLO_HASH);
    return new StorageDriverClaim(claim);
  }

  private static GcsStorageException failure(CompletableFuture<?> future) {
    CompletionException error = assertThrows(CompletionException.class, future::join);
    assertTrue(String.valueOf(error.getCause()), error.getCause() instanceof GcsStorageException);
    return (GcsStorageException) error.getCause();
  }

  private static StorageDriverStoreContext storeContext(TestToken token) {
    return new StorageDriverStoreContext() {
      @Override
      public io.temporal.payload.storage.StorageDriverTargetInfo getTarget() {
        return null;
      }

      @Override
      public CancellationToken<CancellationException> getCancellationToken() {
        return token;
      }
    };
  }

  private static StorageDriverRetrieveContext retrieveContext(TestToken token) {
    return new StorageDriverRetrieveContext() {
      @Override
      public CancellationToken<CancellationException> getCancellationToken() {
        return token;
      }
    };
  }

  private static class InMemoryClient implements GcsStorageDriverClient {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicInteger putAttempts = new AtomicInteger();
    final AtomicInteger getAttempts = new AtomicInteger();
    RuntimeException putError;

    @Override
    public CompletableFuture<Void> putObjectIfAbsent(String bucket, String name, byte[] data) {
      putAttempts.incrementAndGet();
      if (putError != null) {
        return CompletableFutures.failedFuture(putError);
      }
      objects.putIfAbsent(bucket + "/" + name, data.clone());
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<byte[]> getObject(String bucket, String name) {
      getAttempts.incrementAndGet();
      byte[] data = objects.get(bucket + "/" + name);
      return data == null
          ? CompletableFutures.failedFuture(new RuntimeException("not found"))
          : CompletableFuture.completedFuture(data.clone());
    }
  }

  private static final class ControlledClient extends InMemoryClient {
    final List<CompletableFuture<Void>> uploads = new ArrayList<>();
    final List<CompletableFuture<byte[]>> downloads = new ArrayList<>();

    @Override
    public CompletableFuture<Void> putObjectIfAbsent(String bucket, String name, byte[] data) {
      CompletableFuture<Void> result = new CompletableFuture<>();
      uploads.add(result);
      return result;
    }

    @Override
    public CompletableFuture<byte[]> getObject(String bucket, String name) {
      CompletableFuture<byte[]> result = new CompletableFuture<>();
      downloads.add(result);
      return result;
    }
  }

  private static final class TestToken implements CancellationToken<CancellationException> {
    boolean cancelled;
    Runnable callback;

    void cancel() {
      cancelled = true;
      if (callback != null) {
        callback.run();
      }
    }

    @Override
    public boolean isCancellationRequested() {
      return cancelled;
    }

    @Override
    public void throwIfCancellationRequested() {
      if (cancelled) {
        throw new CancellationException();
      }
    }

    @Override
    public Registration onCancel(Runnable callback) {
      this.callback = callback;
      if (cancelled) {
        callback.run();
      }
      return () -> this.callback = null;
    }
  }
}
