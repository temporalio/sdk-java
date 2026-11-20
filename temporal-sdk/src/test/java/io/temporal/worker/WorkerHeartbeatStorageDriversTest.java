package io.temporal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.worker.v1.StorageDriverInfo;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote("This test directly creates and controls a local test service.")
@Category(RequiresLocalServer.class)
public class WorkerHeartbeatStorageDriversTest {

  @Test
  public void driverTypesAreReportedOnce() {
    StorageDriver first = driver("first", "test.type-a");
    ExternalStorage externalStorage =
        ExternalStorage.newBuilder()
            .setDrivers(
                Arrays.asList(
                    first, driver("second", "test.type-a"), driver("third", "test.type-b")))
            .setDriverSelector((context, payload) -> first)
            .build();

    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
      WorkflowClient client =
          WorkflowClient.newInstance(
              env.getWorkflowServiceStubs(),
              WorkflowClientOptions.newBuilder()
                  .setNamespace(env.getNamespace())
                  .setExternalStorage(externalStorage)
                  .build());
      WorkerFactory factory = WorkerFactory.newInstance(client);
      Worker worker = factory.newWorker("test-queue");
      factory.start();

      assertEquals(Arrays.asList("test.type-a", "test.type-b"), reportedDriverTypes(worker));

      factory.shutdownNow();
    }
  }

  @Test
  public void noDriversAreReportedWithoutExternalStorage() {
    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
      WorkerFactory factory = env.getWorkerFactory();
      Worker worker = factory.newWorker("test-queue");
      factory.start();

      assertTrue(reportedDriverTypes(worker).isEmpty());

      factory.shutdownNow();
    }
  }

  private static List<String> reportedDriverTypes(Worker worker) {
    return worker.buildHeartbeatCallback("test-worker-group", null).get().getDriversList().stream()
        .map(StorageDriverInfo::getType)
        .collect(Collectors.toList());
  }

  private static StorageDriver driver(String name, String type) {
    return new StorageDriver() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getType() {
        return type;
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
