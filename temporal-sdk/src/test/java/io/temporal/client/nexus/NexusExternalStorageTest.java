package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusServiceClient;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * End-to-end coverage of external storage on a standalone Nexus operation
 */
public class NexusExternalStorageTest {

  private static final RecordingDriver driver = new RecordingDriver("nexus-test");

  private static final ExternalStorage storage =
      ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder().setExternalStorage(storage).build())
          .build();

  @Before
  public void requireStandaloneNexusSupport() {
    assumeTrue(
        "server does not support standalone Nexus operations",
        testWorkflowRule.isUseExternalService());
    driver.reset();
  }

  @Test
  public void operationInputAndResultRoundTripThroughStorage() {
    String input = "extstore-input-" + UUID.randomUUID();

    String result =
        buildServiceClient()
            .execute(TestNexusServices.TestNexusService1::operation, newOptionsWithId(), input);

    Assert.assertEquals("echo:" + input, result);
    Assert.assertTrue(
        "expected the operation input to be offloaded to the driver", driver.stored(input));
    Assert.assertTrue(
        "expected the operation result to be offloaded to the driver",
        driver.stored("echo:" + input));
    Assert.assertTrue(
        "expected the driver to be read back on retrieval", driver.retrieves.get() > 0);
  }

  /**
   * A handler that receives an unresolved reference cannot deserialize its input, so the handler
   * observing the original value is what proves the inbound retrieval ran.
   */
  @Test
  public void handlerReceivesTheResolvedInput() {
    String input = "extstore-inbound-" + UUID.randomUUID();

    String result =
        buildServiceClient()
            .execute(TestNexusServices.TestNexusService1::operation, newOptionsWithId(), input);

    Assert.assertEquals("echo:" + input, result);
  }

  @Test
  public void nexusPayloadsAreStoredWithoutATarget() {
    buildServiceClient()
        .execute(
            TestNexusServices.TestNexusService1::operation,
            newOptionsWithId(),
            "extstore-target-" + UUID.randomUUID());

    Assert.assertFalse("expected the driver to have been used", driver.targets.isEmpty());
    Assert.assertTrue(
        "Nexus payloads are stored without a StorageDriverTargetInfo",
        driver.targets.stream().allMatch(target -> target == null));
  }

  private static StartNexusOperationOptions newOptionsWithId() {
    return StartNexusOperationOptions.newBuilder().setId(UUID.randomUUID().toString()).build();
  }

  private NexusServiceClient<TestNexusServices.TestNexusService1> buildServiceClient() {
    NexusClient nexusClient =
        NexusClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            NexusClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setExternalStorage(storage)
                .build());
    return nexusClient.newNexusServiceClient(
        TestNexusServices.TestNexusService1.class,
        testWorkflowRule.getNexusEndpoint().getSpec().getName());
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }

  private static final class RecordingDriver implements StorageDriver {
    private final String name;
    private final Map<String, Payload> objects = new HashMap<>();
    private final List<String> storedData = new CopyOnWriteArrayList<>();
    final List<StorageDriverTargetInfo> targets = new CopyOnWriteArrayList<>();
    final AtomicInteger retrieves = new AtomicInteger();
    private int counter = 0;

    RecordingDriver(String name) {
      this.name = name;
    }

    synchronized void reset() {
      objects.clear();
      storedData.clear();
      targets.clear();
      retrieves.set(0);
    }

    boolean stored(String substring) {
      return storedData.stream().anyMatch(data -> data.contains(substring));
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.nexus.inmemory";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        targets.add(context.getTarget());
        storedData.add(payload.getData().toStringUtf8());
        String key = name + "-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      retrieves.incrementAndGet();
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
