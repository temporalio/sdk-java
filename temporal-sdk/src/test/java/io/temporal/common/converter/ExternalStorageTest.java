package io.temporal.common.converter;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.Payload;
import java.util.*;
import org.junit.Test;

public class ExternalStorageTest {

  @Test
  public void testBuilderDefaults() {
    StorageDriver driver = new InMemoryStorageDriver("test");
    ExternalStorage es = ExternalStorage.newBuilder().addDriver(driver).build();
    assertEquals(ExternalStorage.DEFAULT_PAYLOAD_SIZE_THRESHOLD, es.getPayloadSizeThreshold());
    assertEquals(1, es.getDrivers().size());
    assertNull(es.getDriverSelector());
  }

  @Test
  public void testCustomThreshold() {
    StorageDriver driver = new InMemoryStorageDriver("test");
    ExternalStorage es =
        ExternalStorage.newBuilder().addDriver(driver).setPayloadSizeThreshold(1024).build();
    assertEquals(1024, es.getPayloadSizeThreshold());
  }

  @Test(expected = IllegalStateException.class)
  public void testNoDriversFails() {
    ExternalStorage.newBuilder().build();
  }

  @Test(expected = IllegalStateException.class)
  public void testMultiDriversWithoutSelectorFails() {
    ExternalStorage.newBuilder()
        .addDriver(new InMemoryStorageDriver("a"))
        .addDriver(new InMemoryStorageDriver("b"))
        .build();
  }

  @Test
  public void testMultiDriversWithSelector() {
    StorageDriver driverA = new InMemoryStorageDriver("a");
    StorageDriver driverB = new InMemoryStorageDriver("b");
    ExternalStorage es =
        ExternalStorage.newBuilder()
            .addDriver(driverA)
            .addDriver(driverB)
            .setDriverSelector((context, drivers) -> drivers.get(0))
            .build();
    assertEquals(2, es.getDrivers().size());
  }

  @Test
  public void testSelectDriverSingleDriver() {
    StorageDriver driver = new InMemoryStorageDriver("test");
    ExternalStorage es = ExternalStorage.newBuilder().addDriver(driver).build();
    StorageDriverStoreContext ctx = StorageDriverStoreContext.forWorkflow("ns", "wf", null);
    assertSame(driver, es.selectDriver(ctx));
  }

  @Test
  public void testFindDriverByName() {
    StorageDriver driverA = new InMemoryStorageDriver("a");
    StorageDriver driverB = new InMemoryStorageDriver("b");
    ExternalStorage es =
        ExternalStorage.newBuilder()
            .addDriver(driverA)
            .addDriver(driverB)
            .setDriverSelector((context, drivers) -> drivers.get(0))
            .build();
    assertSame(driverB, es.findDriverByName("b"));
  }

  @Test(expected = StorageDriverException.class)
  public void testFindDriverByNameNotFound() {
    StorageDriver driver = new InMemoryStorageDriver("test");
    ExternalStorage es = ExternalStorage.newBuilder().addDriver(driver).build();
    es.findDriverByName("nonexistent");
  }

  @Test(expected = IllegalArgumentException.class)
  public void testInvalidThreshold() {
    ExternalStorage.newBuilder()
        .addDriver(new InMemoryStorageDriver("test"))
        .setPayloadSizeThreshold(0);
  }

  @Test
  public void testClaimToPayloadAndBack() {
    Map<String, String> data = new HashMap<>();
    data.put("bucket", "my-bucket");
    data.put("key", "some/object/key");
    StorageDriverClaim claim = new StorageDriverClaim(data);

    Payload claimPayload = CodecDataConverter.claimToPayload("my-driver", claim);
    assertTrue(CodecDataConverter.isClaimPayload(claimPayload));
    assertEquals("my-driver", CodecDataConverter.getClaimDriverName(claimPayload));

    StorageDriverClaim roundTripped = CodecDataConverter.payloadToClaim(claimPayload);
    assertEquals("my-bucket", roundTripped.getClaimData().get("bucket"));
    assertEquals("some/object/key", roundTripped.getClaimData().get("key"));
  }

  @Test
  public void testNonClaimPayloadNotDetected() {
    Payload normalPayload =
        Payload.newBuilder()
            .putMetadata("encoding", com.google.protobuf.ByteString.copyFromUtf8("json/plain"))
            .build();
    assertFalse(CodecDataConverter.isClaimPayload(normalPayload));
  }

  @Test
  public void testStorageDriverClaimEquality() {
    Map<String, String> data1 = new HashMap<>();
    data1.put("key", "value");
    Map<String, String> data2 = new HashMap<>();
    data2.put("key", "value");

    StorageDriverClaim claim1 = new StorageDriverClaim(data1);
    StorageDriverClaim claim2 = new StorageDriverClaim(data2);
    assertEquals(claim1, claim2);
    assertEquals(claim1.hashCode(), claim2.hashCode());
  }

  /** A simple in-memory storage driver for testing. */
  static class InMemoryStorageDriver implements StorageDriver {
    private final String driverName;
    private final Map<String, byte[]> storage = new HashMap<>();

    InMemoryStorageDriver(String name) {
      this.driverName = name;
    }

    @Override
    public String name() {
      return driverName;
    }

    @Override
    public String type() {
      return "in-memory";
    }

    @Override
    public List<StorageDriverClaim> store(
        StorageDriverStoreContext context, List<byte[]> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (byte[] payload : payloads) {
        String key = UUID.randomUUID().toString();
        storage.put(key, payload);
        Map<String, String> claimData = new HashMap<>();
        claimData.put("key", key);
        claims.add(new StorageDriverClaim(claimData));
      }
      return claims;
    }

    @Override
    public List<byte[]> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      List<byte[]> results = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        String key = claim.getClaimData().get("key");
        byte[] data = storage.get(key);
        if (data == null) {
          throw new StorageDriverException("Key not found: " + key);
        }
        results.add(data);
      }
      return results;
    }
  }
}
