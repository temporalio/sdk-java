package io.temporal.payload.storage.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.payload.storage.StorageDriverActivityInfo;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class S3StorageDriverTest {

  private static Payload payload(String data) {
    return Payload.newBuilder()
        .putMetadata("encoding", ByteString.copyFromUtf8("binary/plain"))
        .setData(ByteString.copyFromUtf8(data))
        .build();
  }

  private static S3StorageDriver driver(S3Client client) {
    return S3StorageDriver.newBuilder().setClient(client).setBucket("test-bucket").build();
  }

  private static StorageDriverStoreContext storeContext() {
    return () -> null;
  }

  private static StorageDriverStoreContext storeContext(StorageDriverTargetInfo target) {
    return () -> target;
  }

  private static final StorageDriverRetrieveContext RETRIEVE_CONTEXT =
      new StorageDriverRetrieveContext() {};

  /** Joins a future expected to fail and returns the message of the underlying cause. */
  private static String failureMessage(CompletableFuture<?> future) {
    try {
      future.join();
    } catch (CompletionException e) {
      Throwable cause = e;
      while (cause instanceof CompletionException && cause.getCause() != null) {
        cause = cause.getCause();
      }
      return cause.getMessage();
    }
    fail("expected the future to fail");
    return null;
  }

  // --- Builder ---

  @Test
  public void builderDefaults() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    assertEquals("aws.s3driver", driver.getName());
    assertEquals("aws.s3driver", driver.getType());
  }

  @Test
  public void builderCustomName() {
    S3StorageDriver driver =
        S3StorageDriver.newBuilder()
            .setClient(new InMemoryS3Client())
            .setBucket("b")
            .setName("custom-name")
            .build();
    assertEquals("custom-name", driver.getName());
  }

  @Test(expected = IllegalStateException.class)
  public void builderRequiresClient() {
    S3StorageDriver.newBuilder().setBucket("b").build();
  }

  @Test(expected = IllegalStateException.class)
  public void builderRequiresBucket() {
    S3StorageDriver.newBuilder().setClient(new InMemoryS3Client()).build();
  }

  @Test(expected = IllegalStateException.class)
  public void builderRejectsNonPositiveMaxPayloadSize() {
    S3StorageDriver.newBuilder()
        .setClient(new InMemoryS3Client())
        .setBucket("b")
        .setMaxPayloadSize(0)
        .build();
  }

  @Test(expected = IllegalStateException.class)
  public void builderRejectsBothBucketAndResolver() {
    S3StorageDriver.newBuilder()
        .setClient(new InMemoryS3Client())
        .setBucket("b")
        .setBucketResolver((context, payload) -> "other")
        .build();
  }

  // --- Store ---

  @Test
  public void storeSinglePayloadProducesClaim() {
    S3StorageDriver driver = driver(new InMemoryS3Client());

    List<StorageDriverClaim> claims =
        driver.store(storeContext(), Collections.singletonList(payload("hello"))).join();

    assertEquals(1, claims.size());
    Map<String, String> claimData = claims.get(0).getClaimData();
    assertEquals("test-bucket", claimData.get("bucket"));
    assertEquals("sha256", claimData.get("hash_algorithm"));
    assertFalse(claimData.get("hash_value").isEmpty());
    assertEquals("v0/d/sha256/" + claimData.get("hash_value"), claimData.get("key"));
  }

  @Test
  public void storeEmptyPayloadsProducesNoClaims() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    assertTrue(driver.store(storeContext(), Collections.emptyList()).join().isEmpty());
  }

  @Test
  public void storeDeduplicatesIdenticalPayloads() {
    InMemoryS3Client client = new InMemoryS3Client();
    S3StorageDriver driver = driver(client);
    Payload p = payload("duplicate-me");

    driver.store(storeContext(), Collections.singletonList(p)).join();
    assertEquals(1, client.putCount.get());

    driver.store(storeContext(), Collections.singletonList(p)).join();
    assertEquals(1, client.putCount.get());
  }

  @Test
  public void storeMultiplePayloadsProducesDistinctKeys() {
    S3StorageDriver driver = driver(new InMemoryS3Client());

    List<StorageDriverClaim> claims =
        driver
            .store(storeContext(), Arrays.asList(payload("a"), payload("b"), payload("c")))
            .join();

    assertEquals(3, claims.size());
    assertEquals(3, claims.stream().map(c -> c.getClaimData().get("key")).distinct().count());
  }

  @Test
  public void storeRejectsOversizedPayload() {
    S3StorageDriver driver =
        S3StorageDriver.newBuilder()
            .setClient(new InMemoryS3Client())
            .setBucket("b")
            .setMaxPayloadSize(10)
            .build();

    String message =
        failureMessage(
            driver.store(
                storeContext(),
                Collections.singletonList(payload("definitely longer than ten bytes"))));
    assertTrue(
        message, message.contains("payload size ") && message.contains("exceeds maximum 10"));
  }

  @Test
  public void storeUploadsNothingWhenAnyPayloadFailsValidation() {
    InMemoryS3Client client = new InMemoryS3Client();
    Payload small = payload("small");
    Payload oversized = payload(String.join("", Collections.nCopies(1000, "x")));
    S3StorageDriver driver =
        S3StorageDriver.newBuilder()
            .setClient(client)
            .setBucket("b")
            .setMaxPayloadSize(small.getSerializedSize())
            .build();

    // The valid payload precedes the oversized one; validation must reject the batch before any
    // upload starts, leaving nothing written to S3.
    failureMessage(driver.store(storeContext(), Arrays.asList(small, oversized)));
    assertEquals(0, client.putCount.get());
  }

  @Test
  public void storeResolvesBucketPerPayload() {
    S3StorageDriver driver =
        S3StorageDriver.newBuilder()
            .setClient(new InMemoryS3Client())
            .setBucketResolver(
                (context, payload) ->
                    "a".equals(payload.getData().toStringUtf8()) ? "bucket-a" : "bucket-b")
            .build();

    List<StorageDriverClaim> claims =
        driver.store(storeContext(), Arrays.asList(payload("a"), payload("b"))).join();

    assertEquals("bucket-a", claims.get(0).getClaimData().get("bucket"));
    assertEquals("bucket-b", claims.get(1).getClaimData().get("bucket"));
  }

  @Test
  public void storeWrapsUploadErrorWithContext() {
    InMemoryS3Client client = new InMemoryS3Client();
    client.putError = new RuntimeException("access denied");
    S3StorageDriver driver = driver(client);

    String message =
        failureMessage(driver.store(storeContext(), Collections.singletonList(payload("x"))));
    assertTrue(message, message.startsWith("upload failed [bucket=test-bucket, key="));
    assertTrue(message, message.endsWith(", client_region=ap-southeast-2]: access denied"));
  }

  @Test
  public void storeWrapsExistenceCheckErrorWithContext() {
    InMemoryS3Client client = new InMemoryS3Client();
    client.existsError = new RuntimeException("network timeout");
    S3StorageDriver driver = driver(client);

    String message =
        failureMessage(driver.store(storeContext(), Collections.singletonList(payload("x"))));
    assertTrue(message, message.startsWith("existence check failed [bucket=test-bucket, key="));
    assertTrue(message, message.endsWith(", client_region=ap-southeast-2]: network timeout"));
  }

  // --- Store with target identity ---

  @Test
  public void storeKeyIncludesWorkflowTarget() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    StorageDriverTargetInfo target =
        new StorageDriverWorkflowInfo("default", "wf-123", "run-456", "MyWorkflow");

    String key =
        driver
            .store(storeContext(target), Collections.singletonList(payload("p")))
            .join()
            .get(0)
            .getClaimData()
            .get("key");
    assertTrue(key, key.startsWith("v0/ns/default/wt/MyWorkflow/wi/wf-123/ri/run-456/d/sha256/"));
  }

  @Test
  public void storeKeyIncludesActivityTarget() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    StorageDriverTargetInfo target =
        new StorageDriverActivityInfo("default", "act-789", "run-abc", "MyActivity");

    String key =
        driver
            .store(storeContext(target), Collections.singletonList(payload("p")))
            .join()
            .get(0)
            .getClaimData()
            .get("key");
    assertTrue(key, key.startsWith("v0/ns/default/at/MyActivity/ai/act-789/ri/run-abc/d/sha256/"));
  }

  @Test
  public void storeKeyPercentEncodesSpecialChars() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    StorageDriverTargetInfo target =
        new StorageDriverWorkflowInfo("my namespace", "wf id+1", "run=abc", "my/workflow");

    String key =
        driver
            .store(storeContext(target), Collections.singletonList(payload("p")))
            .join()
            .get(0)
            .getClaimData()
            .get("key");
    assertTrue(
        key,
        key.startsWith("v0/ns/my%20namespace/wt/my%2Fworkflow/wi/wf%20id+1/ri/run=abc/d/sha256/"));
  }

  @Test
  public void storageKeyEscapesPathSegmentsByContract() {
    assertEquals("null", S3StorageKey.escapePathSegment(null));
    assertEquals("null", S3StorageKey.escapePathSegment(""));
    assertEquals("azAZ09-_.~$&+:=@", S3StorageKey.escapePathSegment("azAZ09-_.~$&+:=@"));
    assertEquals(
        "space%20slash%2Fpercent%25snowman%E2%98%83",
        S3StorageKey.escapePathSegment("space slash/percent%snowman\u2603"));
  }

  @Test
  public void storageKeyReadmeExamples() {
    // Segment encoding examples.
    assertEquals("my%20namespace", S3StorageKey.escapePathSegment("my namespace"));
    assertEquals("my%2Fworkflow", S3StorageKey.escapePathSegment("my/workflow"));
    assertEquals("wf%20id+1", S3StorageKey.escapePathSegment("wf id+1"));
    assertEquals("attempt=1", S3StorageKey.escapePathSegment("attempt=1"));

    String workflowDigest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    String activityDigest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    String fallbackDigest = "486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7";

    // Workflow full-key example.
    assertEquals(
        "v0/ns/payments%20prod/wt/ChargeWorkflow/wi/order+123=abc/ri/3f1d6c7a-8b2e-4f7a-9d0a-87a6f95e4d31/d/sha256/"
            + workflowDigest,
        S3StorageKey.forPayload(
            new StorageDriverWorkflowInfo(
                "payments prod",
                "order+123=abc",
                "3f1d6c7a-8b2e-4f7a-9d0a-87a6f95e4d31",
                "ChargeWorkflow"),
            "sha256",
            workflowDigest));

    // Activity full-key example.
    assertEquals(
        "v0/ns/payments%20prod/at/Capture%2FCharge/ai/activity%20id+42/ri/9e1d1fd9-2f8a-4c40-93e2-731f31b9268b/d/sha256/"
            + activityDigest,
        S3StorageKey.forPayload(
            new StorageDriverActivityInfo(
                "payments prod",
                "activity id+42",
                "9e1d1fd9-2f8a-4c40-93e2-731f31b9268b",
                "Capture/Charge"),
            "sha256",
            activityDigest));

    // Fallback full-key example.
    assertEquals(
        "v0/d/sha256/" + fallbackDigest, S3StorageKey.forPayload(null, "sha256", fallbackDigest));
  }

  @Test
  public void storeSamePayloadDifferentTargetsProducesDifferentKeys() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    Payload p = payload("shared");

    String wfKey =
        driver
            .store(
                storeContext(new StorageDriverWorkflowInfo("ns", "wf-1", "run-1", "WF")),
                Collections.singletonList(p))
            .join()
            .get(0)
            .getClaimData()
            .get("key");
    String actKey =
        driver
            .store(
                storeContext(new StorageDriverActivityInfo("ns", "act-1", "run-1", "ACT")),
                Collections.singletonList(p))
            .join()
            .get(0)
            .getClaimData()
            .get("key");
    assertNotEquals(wfKey, actKey);
  }

  // --- Retrieve ---

  @Test
  public void retrieveRoundTrip() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    Payload original = payload("round-trip data");

    List<StorageDriverClaim> claims =
        driver.store(storeContext(), Collections.singletonList(original)).join();
    List<Payload> restored = driver.retrieve(RETRIEVE_CONTEXT, claims).join();

    assertEquals(1, restored.size());
    assertEquals(original, restored.get(0));
  }

  @Test
  public void retrieveRoundTripMultiplePreservesOrder() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    List<Payload> originals = Arrays.asList(payload("x"), payload("y"), payload("z"));

    List<StorageDriverClaim> claims = driver.store(storeContext(), originals).join();
    List<Payload> restored = driver.retrieve(RETRIEVE_CONTEXT, claims).join();

    assertEquals(originals, restored);
  }

  @Test
  public void retrieveDetectsCorruptedData() {
    InMemoryS3Client client = new InMemoryS3Client();
    S3StorageDriver driver = driver(client);

    List<StorageDriverClaim> claims =
        driver.store(storeContext(), Collections.singletonList(payload("legit"))).join();
    client.objects.replaceAll((k, v) -> "corrupted".getBytes());

    String message = failureMessage(driver.retrieve(RETRIEVE_CONTEXT, claims));
    assertTrue(message, message.startsWith("integrity check failed [bucket=test-bucket, key="));
  }

  @Test
  public void retrieveRejectsUnsupportedHashAlgorithm() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    List<StorageDriverClaim> claims =
        driver.store(storeContext(), Collections.singletonList(payload("data"))).join();

    Map<String, String> tampered = new HashMap<>(claims.get(0).getClaimData());
    tampered.put("hash_algorithm", "md5");

    String message =
        failureMessage(
            driver.retrieve(
                RETRIEVE_CONTEXT, Collections.singletonList(new StorageDriverClaim(tampered))));
    assertEquals("unsupported hash algorithm \"md5\"", message);
  }

  @Test
  public void retrieveRejectsClaimMissingBucket() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    StorageDriverClaim claim =
        new StorageDriverClaim(Collections.singletonMap("key", "v0/d/sha256/abc"));

    assertEquals(
        "claim missing field \"bucket\"",
        failureMessage(driver.retrieve(RETRIEVE_CONTEXT, Collections.singletonList(claim))));
  }

  @Test
  public void retrieveRejectsClaimMissingKey() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    StorageDriverClaim claim =
        new StorageDriverClaim(Collections.singletonMap("bucket", "test-bucket"));

    assertEquals(
        "claim missing field \"key\"",
        failureMessage(driver.retrieve(RETRIEVE_CONTEXT, Collections.singletonList(claim))));
  }

  @Test
  public void retrieveRejectsClaimMissingHashAlgorithm() {
    S3StorageDriver driver = driver(new InMemoryS3Client());
    List<StorageDriverClaim> claims =
        driver.store(storeContext(), Collections.singletonList(payload("x"))).join();

    Map<String, String> tampered = new HashMap<>(claims.get(0).getClaimData());
    tampered.remove("hash_algorithm");

    assertEquals(
        "claim missing field \"hash_algorithm\"",
        failureMessage(
            driver.retrieve(
                RETRIEVE_CONTEXT, Collections.singletonList(new StorageDriverClaim(tampered)))));
  }

  @Test
  public void retrieveWrapsDownloadErrorWithContext() {
    InMemoryS3Client client = new InMemoryS3Client();
    S3StorageDriver driver = driver(client);
    List<StorageDriverClaim> claims =
        driver.store(storeContext(), Collections.singletonList(payload("data"))).join();

    client.getError = new RuntimeException("throttled");

    String message = failureMessage(driver.retrieve(RETRIEVE_CONTEXT, claims));
    assertTrue(message, message.startsWith("download failed [bucket=test-bucket, key="));
    assertTrue(message, message.endsWith(", client_region=ap-southeast-2]: throttled"));
  }

  /** In-memory {@link S3Client} with optional error injection, for unit tests. */
  private static final class InMemoryS3Client implements S3Client {
    final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    final AtomicInteger putCount = new AtomicInteger();
    RuntimeException putError;
    RuntimeException getError;
    RuntimeException existsError;

    private static String objectKey(String bucket, String key) {
      return bucket + "/" + key;
    }

    @Override
    public CompletableFuture<Void> putObject(String bucket, String key, byte[] data) {
      if (putError != null) {
        return failed(putError);
      }
      putCount.incrementAndGet();
      objects.put(objectKey(bucket, key), data.clone());
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> objectExists(String bucket, String key) {
      if (existsError != null) {
        return failed(existsError);
      }
      return CompletableFuture.completedFuture(objects.containsKey(objectKey(bucket, key)));
    }

    @Override
    public CompletableFuture<byte[]> getObject(String bucket, String key) {
      if (getError != null) {
        return failed(getError);
      }
      byte[] data = objects.get(objectKey(bucket, key));
      if (data == null) {
        return failed(new RuntimeException("not found: " + objectKey(bucket, key)));
      }
      return CompletableFuture.completedFuture(data.clone());
    }

    @Override
    public Map<String, String> describe() {
      return Collections.singletonMap("client_region", "ap-southeast-2");
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
      CompletableFuture<T> future = new CompletableFuture<>();
      future.completeExceptionally(t);
      return future;
    }
  }
}
