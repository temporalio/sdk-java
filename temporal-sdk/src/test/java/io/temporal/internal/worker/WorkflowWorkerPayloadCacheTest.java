package io.temporal.internal.worker;

import com.google.common.collect.ImmutableMap;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.InMemoryPayloadCache;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/** e2e test of a Worker sharing its client's payload cache. */
public class WorkflowWorkerPayloadCacheTest {
  private static final String TASK_QUEUE = "payload-cache";

  private final TestStorageDriver driver = TestStorageDriver.named("wf-cache");
  private final TestStatsReporter reporter = new TestStatsReporter();
  private final Scope metricsScope =
      new RootScopeBuilder().reporter(reporter).reportEvery(com.uber.m3.util.Duration.ofMillis(10));

  private TestWorkflowEnvironment testEnvironment;
  private WorkflowClient client;
  private WorkerFactory workerFactory;

  @Before
  public void setUp() {
    testEnvironment =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder().setMetricsScope(metricsScope).build());
    client =
        WorkflowClient.newInstance(
            testEnvironment.getWorkflowServiceStubs(),
            WorkflowClientOptions.newBuilder()
                .setNamespace(testEnvironment.getNamespace())
                .setExternalStorage(
                    ExternalStorage.newBuilder()
                        .setDriver(driver)
                        .setPayloadSizeThreshold(0)
                        .build())
                .setPayloadCache(new InMemoryPayloadCache())
                .build());
    workerFactory = WorkerFactory.newInstance(client);
    workerFactory.newWorker(TASK_QUEUE).registerWorkflowImplementationTypes(EchoWorkflowImpl.class);
    workerFactory.start();
  }

  @After
  public void tearDown() {
    workerFactory.shutdownNow();
    testEnvironment.close();
  }

  @Test
  public void workerReadsPayloadsTheClientStoredFromTheSharedCache() throws Exception {
    EchoWorkflow workflow =
        client.newWorkflowStub(
            EchoWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(TASK_QUEUE)
                .setWorkflowId("extstore-cache-" + UUID.randomUUID())
                .build());

    Assert.assertEquals("echo: hello", workflow.echo("hello"));
    Assert.assertTrue("payloads should have been offloaded", driver.stores.get() > 0);
    Assert.assertEquals("every retrieve should hit the cache", 0, driver.retrieves.get());

    metricsScope.close();
    Map<String, String> workerTags =
        ImmutableMap.<String, String>builder()
            .putAll(MetricsTag.defaultTags(testEnvironment.getNamespace()))
            .put(MetricsTag.TASK_QUEUE, TASK_QUEUE)
            .buildKeepingLast();
    reporter.assertCounter(
        MetricsType.PAYLOAD_CACHE_LOOKUP_HIT_COUNTER, workerTags, hits -> hits >= 1);
  }

  @WorkflowInterface
  public interface EchoWorkflow {
    @WorkflowMethod
    String echo(String input);
  }

  public static class EchoWorkflowImpl implements EchoWorkflow {
    @Override
    public String echo(String input) {
      return "echo: " + input;
    }
  }
}
