package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableMap;
import com.uber.m3.tally.RootScopeBuilder;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusServiceClient;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class NexusExternalStorageFailureTest {

  private static final FlakyDriver driver = new FlakyDriver("nexus-flaky");

  private static final ExternalStorage storage =
      ExternalStorage.newBuilder().setDriver(driver).setPayloadSizeThreshold(0).build();

  private final TestStatsReporter reporter = new TestStatsReporter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
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
  public void aFailedRetrievalIsReportedAsARetryableHandlerError() {
    String input = "extstore-flaky-" + UUID.randomUUID();
    driver.failNextRetrieves.set(1);

    String result =
        buildServiceClient()
            .execute(TestNexusServices.TestNexusService1::operation, newOptionsWithId(), input);

    Assert.assertEquals("echo:" + input, result);
    Assert.assertTrue(
        "expected the failed retrieval to be retried, attempts=" + driver.retrieveAttempts.get(),
        driver.retrieveAttempts.get() > 1);
    reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags(), 1);
  }

  private Map<String, String> execFailedTags() {
    return ImmutableMap.<String, String>builder()
        .putAll(
            MetricsTag.defaultTags(
                testWorkflowRule.getWorkflowClient().getOptions().getNamespace()))
        .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.NEXUS_WORKER.getValue())
        .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
        .put(MetricsTag.NEXUS_SERVICE, "TestNexusService1")
        .put(MetricsTag.NEXUS_OPERATION, "operation")
        .put(MetricsTag.TASK_FAILURE_TYPE, MetricsTag.TASK_FAILURE_VALUE_HANDLER_ERROR_INTERNAL)
        .buildKeepingLast();
  }

  private static StartNexusOperationOptions newOptionsWithId() {
    return StartNexusOperationOptions.newBuilder()
        .setId(UUID.randomUUID().toString())
        .setScheduleToCloseTimeout(Duration.ofSeconds(60))
        .build();
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

  private static final class FlakyDriver implements StorageDriver {
    private final String name;
    private final Map<String, Payload> objects = new HashMap<>();
    final AtomicInteger failNextRetrieves = new AtomicInteger();
    final AtomicInteger retrieveAttempts = new AtomicInteger();
    private int counter = 0;

    FlakyDriver(String name) {
      this.name = name;
    }

    synchronized void reset() {
      objects.clear();
      failNextRetrieves.set(0);
      retrieveAttempts.set(0);
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return "test.nexus.flaky";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (Payload payload : payloads) {
        String key = name + "-" + (counter++);
        objects.put(key, payload);
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", key)));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public synchronized CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      retrieveAttempts.incrementAndGet();
      if (failNextRetrieves.getAndDecrement() > 0) {
        CompletableFuture<List<Payload>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("storage unavailable"));
        return failed;
      }
      List<Payload> payloads = new ArrayList<>();
      for (StorageDriverClaim claim : claims) {
        payloads.add(objects.get(claim.getClaimData().get("key")));
      }
      return CompletableFuture.completedFuture(payloads);
    }
  }
}
