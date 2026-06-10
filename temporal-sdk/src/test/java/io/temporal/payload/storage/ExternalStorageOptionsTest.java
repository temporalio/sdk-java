package io.temporal.payload.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import io.temporal.api.common.v1.Payload;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class ExternalStorageOptionsTest {

  private static StorageDriverStoreContext storeContext(StorageDriverTargetInfo target) {
    return new StorageDriverStoreContext() {
      @Override
      public StorageDriverTargetInfo getTarget() {
        return target;
      }
    };
  }

  private static StorageDriver driver(String name) {
    return new StorageDriver() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getType() {
        return "test";
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
  public void singleDriverNoSelectorSynthesizesSelector() {
    StorageDriver a = driver("a");
    ExternalStorageOptions storage = ExternalStorageOptions.newBuilder().setDriver(a).build();
    assertEquals(1, storage.getDrivers().size());
    StorageDriverSelector selector = storage.getDriverSelector();
    assertNotNull(selector);
    assertSame(a, selector.selectDriver(storeContext(null), Payload.getDefaultInstance()));
  }

  @Test
  public void multipleDriversWithSelectorIsValid() {
    StorageDriver a = driver("a");
    ExternalStorageOptions storage =
        ExternalStorageOptions.newBuilder()
            .setDrivers(Arrays.asList(a, driver("b")))
            .setDriverSelector((context, payload) -> a)
            .build();
    assertEquals(2, storage.getDrivers().size());
    assertNotNull(storage.getDriverSelector());
  }

  @Test
  public void lastSetDriversWins() {
    StorageDriver a = driver("a");
    StorageDriver b = driver("b");
    StorageDriver c = driver("c");
    ExternalStorageOptions storage =
        ExternalStorageOptions.newBuilder()
            .setDrivers(Arrays.asList(a, b))
            .setDrivers(Collections.singletonList(c))
            .build();
    assertEquals(Collections.singletonList(c), storage.getDrivers());
  }

  @Test
  public void zeroThresholdStoresAll() {
    ExternalStorageOptions storage =
        ExternalStorageOptions.newBuilder()
            .setDrivers(Collections.singletonList(driver("a")))
            .setPayloadSizeThreshold(0)
            .build();
    assertEquals(0, storage.getPayloadSizeThreshold());
  }

  @Test(expected = IllegalStateException.class)
  public void noDriversRejected() {
    ExternalStorageOptions.newBuilder().build();
  }

  @Test(expected = IllegalStateException.class)
  public void duplicateDriverNamesRejected() {
    ExternalStorageOptions.newBuilder().setDrivers(Arrays.asList(driver("dup"), driver("dup"))).build();
  }

  @Test(expected = IllegalStateException.class)
  public void multipleDriversRequireSelector() {
    ExternalStorageOptions.newBuilder().setDrivers(Arrays.asList(driver("a"), driver("b"))).build();
  }

  @Test(expected = IllegalStateException.class)
  public void negativeThresholdRejected() {
    ExternalStorageOptions.newBuilder()
        .setDrivers(Collections.singletonList(driver("a")))
        .setPayloadSizeThreshold(-1)
        .build();
  }
}
