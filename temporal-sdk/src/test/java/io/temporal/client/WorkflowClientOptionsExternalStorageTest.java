package io.temporal.client;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.temporal.api.common.v1.Payload;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class WorkflowClientOptionsExternalStorageTest {

  @Test
  public void defaultsToDisabled() {
    assertNull(WorkflowClientOptions.newBuilder().build().getExternalStorage());
    assertNull(WorkflowClientOptions.getDefaultInstance().getExternalStorage());
  }

  @Test
  public void buildsWithDefaults() {
    ExternalStorage storage = storage();

    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder()
            .setExternalStorage(storage)
            .validateAndBuildWithDefaults();

    assertSame(storage, options.getExternalStorage());
  }

  /** Plugins reconfigure a client by rebuilding its options, so a round trip must not drop it. */
  @Test
  public void survivesRoundTripThroughBuilder() {
    ExternalStorage storage = storage();

    WorkflowClientOptions original =
        WorkflowClientOptions.newBuilder().setExternalStorage(storage).build();

    assertSame(storage, original.toBuilder().build().getExternalStorage());
    assertSame(storage, WorkflowClientOptions.newBuilder(original).build().getExternalStorage());
  }

  private static ExternalStorage storage() {
    return ExternalStorage.newBuilder().setDriver(driver()).build();
  }

  private static StorageDriver driver() {
    return new StorageDriver() {
      @Override
      public String getName() {
        return "test-driver";
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
}
