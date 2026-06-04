package io.temporal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.temporal.api.common.v1.Payload;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class ExternalStorageTest {

  private static StorageDriver driver(String name) {
    return new StorageDriver() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public CompletableFuture<List<StorageDriverClaim>> store(
          StorageDriverStoreContext context, List<Payload> payloads) {
        throw new UnsupportedOperationException();
      }

      @Override
      public CompletableFuture<List<Payload>> retrieve(
          StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Test
  public void singleDriverNoSelectorIsValid() {
    ExternalStorage storage =
        ExternalStorage.newBuilder().setDrivers(Collections.singletonList(driver("a"))).build();
    assertEquals(1, storage.getDrivers().size());
    assertNull(storage.getDriverSelector());
  }

  @Test
  public void multipleDriversWithSelectorIsValid() {
    StorageDriver a = driver("a");
    ExternalStorage storage =
        ExternalStorage.newBuilder()
            .setDrivers(Arrays.asList(a, driver("b")))
            .setDriverSelector((context, payload) -> a)
            .build();
    assertEquals(2, storage.getDrivers().size());
    assertNotNull(storage.getDriverSelector());
  }

  @Test
  public void nullThresholdStoresAll() {
    ExternalStorage storage =
        ExternalStorage.newBuilder()
            .setDrivers(Collections.singletonList(driver("a")))
            .setPayloadSizeThreshold(null)
            .build();
    assertNull(storage.getPayloadSizeThreshold());
  }

  @Test(expected = IllegalArgumentException.class)
  public void noDriversRejected() {
    ExternalStorage.newBuilder().build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void duplicateDriverNamesRejected() {
    ExternalStorage.newBuilder().setDrivers(Arrays.asList(driver("dup"), driver("dup"))).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void multipleDriversRequireSelector() {
    ExternalStorage.newBuilder().setDrivers(Arrays.asList(driver("a"), driver("b"))).build();
  }

  @Test(expected = IllegalArgumentException.class)
  public void negativeThresholdRejected() {
    ExternalStorage.newBuilder()
        .setDrivers(Collections.singletonList(driver("a")))
        .setPayloadSizeThreshold(-1)
        .build();
  }
}
