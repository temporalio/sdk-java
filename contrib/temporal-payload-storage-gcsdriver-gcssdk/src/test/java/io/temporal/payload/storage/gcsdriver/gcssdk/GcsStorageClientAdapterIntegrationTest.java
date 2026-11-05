package io.temporal.payload.storage.gcsdriver.gcssdk;

import static org.junit.Assert.*;

import com.google.cloud.NoCredentials;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import io.temporal.payload.storage.gcsdriver.GcsStorageDriver;
import io.temporal.payload.storage.gcsdriver.GcsStorageException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

public class GcsStorageClientAdapterIntegrationTest {
  @ClassRule public static final FakeGcsServer SERVER = new FakeGcsServer();

  private static final StorageDriverRetrieveContext RETRIEVE_CONTEXT =
      new StorageDriverRetrieveContext() {};

  private Storage storage;
  private ExecutorService executor;
  private GcsStorageClientAdapter adapter;
  private GcsStorageDriver driver;
  private String bucket;

  @Before
  public void setUp() {
    storage =
        StorageOptions.newBuilder()
            .setHost(SERVER.endpoint())
            .setProjectId("temporal-test")
            .setCredentials(NoCredentials.getInstance())
            .setTransportOptions(
                HttpTransportOptions.newBuilder()
                    .setConnectTimeout(5000)
                    .setReadTimeout(5000)
                    .build())
            .build()
            .getService();
    executor = Executors.newFixedThreadPool(4);
    adapter = new GcsStorageClientAdapter(storage, executor);
    bucket = "temporal-test-" + UUID.randomUUID();
    storage.create(BucketInfo.of(bucket));
    driver = GcsStorageDriver.newBuilder().setClient(adapter).setBucket(bucket).build();
  }

  @After
  public void tearDown() throws Exception {
    executor.shutdownNow();
    assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    try {
      for (Blob blob : storage.list(bucket).iterateAll()) {
        storage.delete(blob.getBlobId());
      }
      storage.delete(bucket);
    } finally {
      storage.close();
    }
  }

  @Test
  public void completePayloadRoundTripWithUnicodeNamesAndLargeData() throws Exception {
    for (int size : new int[] {0, 6 * 1024 * 1024}) {
      Payload original =
          Payload.newBuilder()
              .setData(ByteString.copyFrom(new byte[size]))
              .putMetadata("encoding", ByteString.copyFromUtf8("binary/plain"))
              .putMetadata("custom", ByteString.copyFrom(new byte[] {0, -1}))
              .build();
      StorageDriverStoreContext context =
          () -> new StorageDriverWorkflowInfo("ns /%", "日本語 😀 /%+", null, "Workflow");
      List<StorageDriverClaim> claims =
          driver.store(context, Collections.singletonList(original)).get(10, TimeUnit.SECONDS);
      String name = claims.get(0).getClaimData().get("object_name");
      assertTrue(name.startsWith("v0/ns/ns %2F%25/wt/Workflow/wi/日本語 😀 %2F%25+/ri/null/"));
      assertEquals(4, claims.get(0).getClaimData().size());
      assertEquals(
          Collections.singletonList(original),
          driver.retrieve(RETRIEVE_CONTEXT, claims).get(10, TimeUnit.SECONDS));
      assertArrayEquals(original.toByteArray(), storage.readAllBytes(bucket, name));
    }
  }

  @Test
  public void concurrentCreatesAndRetriesPreserveExistingObject() throws Exception {
    String name = "concurrent-object";
    List<CompletableFuture<Void>> uploads = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      uploads.add(adapter.putObjectIfAbsent(bucket, name, new byte[] {(byte) i}));
    }
    CompletableFuture.allOf(uploads.toArray(new CompletableFuture<?>[0])).get(10, TimeUnit.SECONDS);
    byte[] stored = adapter.getObject(bucket, name).get(5, TimeUnit.SECONDS);
    assertEquals(1, stored.length);
    assertTrue(stored[0] >= 0 && stored[0] < 5);
    Long generation = storage.get(bucket, name).getGeneration();
    adapter.putObjectIfAbsent(bucket, name, new byte[] {99}).get(5, TimeUnit.SECONDS);
    assertArrayEquals(stored, storage.readAllBytes(bucket, name));
    assertEquals(generation, storage.get(bucket, name).getGeneration());
  }

  @Test
  public void missingObjectsAndBucketsPropagate404() throws Exception {
    assertNotFound(adapter.getObject(bucket, "missing-object"));
    assertNotFound(adapter.getObject(bucket + "-missing", "object"));
    assertNotFound(adapter.putObjectIfAbsent(bucket + "-missing", "object", new byte[] {1}));
  }

  @Test
  public void retrievesCanonicalClaimFromAnotherWriter() throws Exception {
    Payload original = Payload.newBuilder().setData(ByteString.copyFromUtf8("hello")).build();
    String name = "existing/日本語 object";
    storage.create(BlobInfo.newBuilder(bucket, name).build(), original.toByteArray());
    Map<String, String> data = new HashMap<>();
    data.put("bucket", bucket);
    data.put("object_name", name);
    data.put("hash_algorithm", "sha256");
    data.put("hash_value", "02c64c4d9ab7bb8b5007525541131a945744d299fe58b5f02ae02a3fd659fdf1");
    assertEquals(
        Collections.singletonList(original),
        driver
            .retrieve(RETRIEVE_CONTEXT, Collections.singletonList(new StorageDriverClaim(data)))
            .get(5, TimeUnit.SECONDS));
  }

  @Test
  public void longNamesPreserveNamespace() throws Exception {
    String longId = String.join("", Collections.nCopies(400, "日"));
    for (StorageDriverTargetInfo target :
        new StorageDriverTargetInfo[] {
          new StorageDriverWorkflowInfo("ns /%", longId, "run", "Workflow"),
          new StorageDriverActivityInfo("ns /%", longId, "run", "Activity")
        }) {
      Payload original = Payload.newBuilder().setData(ByteString.copyFromUtf8("payload")).build();
      List<StorageDriverClaim> claims =
          driver.store(() -> target, Collections.singletonList(original)).get(5, TimeUnit.SECONDS);
      String name = claims.get(0).getClaimData().get("object_name");
      assertTrue(name, name.startsWith("v0/ns/ns %2F%25/d/sha256/"));
      assertEquals(
          Collections.singletonList(original),
          driver.retrieve(RETRIEVE_CONTEXT, claims).get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void emptyNamespacesUseNull() throws Exception {
    for (StorageDriverTargetInfo target :
        new StorageDriverTargetInfo[] {
          new StorageDriverWorkflowInfo("", "id", null, "Workflow"),
          new StorageDriverActivityInfo("", "id", null, "Activity")
        }) {
      Payload original = Payload.newBuilder().setData(ByteString.copyFromUtf8("payload")).build();
      List<StorageDriverClaim> claims =
          driver.store(() -> target, Collections.singletonList(original)).get(5, TimeUnit.SECONDS);
      assertTrue(claims.get(0).getClaimData().get("object_name").startsWith("v0/ns/null/"));
      assertEquals(
          Collections.singletonList(original),
          driver.retrieve(RETRIEVE_CONTEXT, claims).get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void corruptionFailsIntegrityCheck() throws Exception {
    Payload original = Payload.newBuilder().setData(ByteString.copyFromUtf8("original")).build();
    List<StorageDriverClaim> claims =
        driver.store(() -> null, Collections.singletonList(original)).get(5, TimeUnit.SECONDS);
    String name = claims.get(0).getClaimData().get("object_name");
    storage.create(BlobInfo.newBuilder(bucket, name).build(), new byte[] {1, 2, 3});
    ExecutionException error =
        assertThrows(
            ExecutionException.class,
            () -> driver.retrieve(RETRIEVE_CONTEXT, claims).get(5, TimeUnit.SECONDS));
    assertTrue(error.getCause() instanceof GcsStorageException);
    assertTrue(error.getCause().getMessage().contains("integrity check failed"));
  }

  private static void assertNotFound(CompletableFuture<?> operation) throws Exception {
    ExecutionException error =
        assertThrows(ExecutionException.class, () -> operation.get(5, TimeUnit.SECONDS));
    assertTrue(error.getCause() instanceof StorageException);
    assertEquals(404, ((StorageException) error.getCause()).getCode());
  }
}
