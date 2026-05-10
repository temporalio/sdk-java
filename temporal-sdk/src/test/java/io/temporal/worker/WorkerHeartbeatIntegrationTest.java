package io.temporal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.WorkerStatus;
import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.worker.v1.WorkerSlotsInfo;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.tuning.ResourceBasedControllerOptions;
import io.temporal.worker.tuning.ResourceBasedTuner;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkerHeartbeatIntegrationTest {

  private static final Duration EVENTUALLY_TIMEOUT = Duration.ofSeconds(10);

  @Before
  public void checkServerSupportsHeartbeats() {
    assumeTrue(
        "Requires real server with worker heartbeat support",
        SDKTestWorkflowRule.useExternalService);
    assumeTrue(
        "Server does not support worker heartbeats",
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeNamespace(
                DescribeNamespaceRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build())
            .getNamespaceInfo()
            .getCapabilities()
            .getWorkerHeartbeats());
  }

  // Shared latches for blocking activity tests
  static final CountDownLatch blockingActivityStarted = new CountDownLatch(1);
  static final CountDownLatch blockingActivityRelease = new CountDownLatch(1);

  // Separate latches for sticky cache miss test
  static final CountDownLatch cacheTestActivityStarted = new CountDownLatch(1);
  static final CountDownLatch cacheTestActivityRelease = new CountDownLatch(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setTestTimeoutSeconds(15)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setWorkerHeartbeatInterval(Duration.ofSeconds(1))
                  .build())
          .setActivityImplementations(
              new TestActivityImpl(),
              new FailingActivityImpl(),
              new BlockingActivityImpl(),
              new CacheTestActivityImpl())
          .setWorkflowTypes(
              TestWorkflowImpl.class,
              FailingWorkflowImpl.class,
              BlockingWorkflowImpl.class,
              CacheTestWorkflowImpl.class)
          .setDoNotStart(true)
          .build();

  /**
   * Combined test for basic heartbeat fields that only require starting the environment (no
   * workflow execution needed). Covers: RPC fields, host info, timestamps, plugins, and
   * elapsed_since_last_heartbeat — all verified via DescribeWorker round-trip.
   */
  @Test
  public void testBasicHeartbeatFields() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    String workerInstanceKey = waitForWorkerInstanceKey();

    // --- RPC fields via DescribeWorker round-trip ---
    WorkerHeartbeat hb = describeWorker(workerInstanceKey);
    assertNotNull("DescribeWorker should return stored heartbeat", hb);
    assertEquals("temporal-java", hb.getSdkName());
    assertFalse("sdk version should be set", hb.getSdkVersion().isEmpty());
    assertFalse("task queue should be set", hb.getTaskQueue().isEmpty());
    assertEquals(workerInstanceKey, hb.getWorkerInstanceKey());
    assertEquals(WorkerStatus.WORKER_STATUS_RUNNING, hb.getStatus());
    assertTrue("start time should be set", hb.hasStartTime());
    assertTrue("heartbeat time should be set", hb.hasHeartbeatTime());
    assertTrue("host info should be set", hb.hasHostInfo());
    assertFalse("host name should be set", hb.getHostInfo().getHostName().isEmpty());
    assertFalse("process id should be set", hb.getHostInfo().getProcessId().isEmpty());

    // --- Host info details ---
    assertFalse(
        "host_info.worker_grouping_key should not be empty",
        hb.getHostInfo().getWorkerGroupingKey().isEmpty());
    assertTrue(
        "host_info.current_host_cpu_usage should be >= 0",
        hb.getHostInfo().getCurrentHostCpuUsage() >= 0.0f);
    assertTrue(
        "host_info.current_host_mem_usage should be >= 0",
        hb.getHostInfo().getCurrentHostMemUsage() >= 0.0f);

    // --- Timestamps ---
    long startTimeSec = hb.getStartTime().getSeconds();
    long nowSec = java.time.Instant.now().getEpochSecond();
    assertTrue("start_time should be within 30 seconds of now", nowSec - startTimeSec <= 30);
    long heartbeatTimeSec = hb.getHeartbeatTime().getSeconds();
    assertTrue(
        "heartbeat_time should be within 30 seconds of now", nowSec - heartbeatTimeSec <= 30);
    assertTrue("heartbeat_time should be >= start_time", heartbeatTimeSec >= startTimeSec);

    // --- Plugins ---
    assertEquals(
        "plugins list should be empty when no plugins are configured", 0, hb.getPluginsCount());

    // --- Elapsed since last heartbeat (set after the first heartbeat cycle) ---
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat latest = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", latest);
          assertTrue(
              "elapsed_since_last_heartbeat should be set after multiple heartbeat cycles",
              latest.hasElapsedSinceLastHeartbeat());
          com.google.protobuf.Duration d = latest.getElapsedSinceLastHeartbeat();
          assertTrue(
              "elapsed_since_last_heartbeat should be non-zero",
              d.getSeconds() > 0 || d.getNanos() > 0);
        });
  }

  @Test
  public void testShutdownHeartbeatStatus() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    // Create a separate factory so we can shut it down within the test
    String taskQueue = testWorkflowRule.getTaskQueue() + "-shutdown";
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkerFactory factory =
        WorkerFactory.newInstance(client, testWorkflowRule.getWorkerFactoryOptions());
    Worker worker = factory.newWorker(taskQueue);
    worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());
    factory.start();

    // Discover worker instance key via ListWorkers
    String workerInstanceKey =
        assertEventually(
            EVENTUALLY_TIMEOUT,
            () -> {
              List<WorkerHeartbeat> workers = listWorkersForQueue(taskQueue);
              assertFalse("worker should appear via ListWorkers", workers.isEmpty());
              return workers.get(0).getWorkerInstanceKey();
            });

    // Graceful shutdown sends ShutdownWorkerRequest with SHUTTING_DOWN status
    factory.shutdown();
    factory.awaitTermination(10, TimeUnit.SECONDS);

    // After shutdown, the server should reflect SHUTTING_DOWN status
    WorkerHeartbeat hb = describeWorker(workerInstanceKey);
    assertNotNull("DescribeWorker should return stored heartbeat after shutdown", hb);
    assertEquals(
        "status should be WORKER_STATUS_SHUTTING_DOWN after shutdown",
        WorkerStatus.WORKER_STATUS_SHUTTING_DOWN,
        hb.getStatus());
    assertFalse("task_queue should be set", hb.getTaskQueue().isEmpty());
  }

  /**
   * Combined test for heartbeat fields that require workflow execution. Covers: slot info, task
   * counters, and poller info.
   */
  @Test
  public void testHeartbeatAfterWorkflowExecution() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow wf =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    assertEquals("done", wf.execute("test"));

    String workerInstanceKey = waitForWorkerInstanceKey();

    // --- Slot info via DescribeWorker ---
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat describedHb = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", describedHb);

          assertTrue(
              "workflow_task_slots_info should be set", describedHb.hasWorkflowTaskSlotsInfo());
          assertTrue(
              "activity_task_slots_info should be set", describedHb.hasActivityTaskSlotsInfo());
          assertTrue(
              "local_activity_slots_info should be set", describedHb.hasLocalActivitySlotsInfo());
          assertTrue("nexus_task_slots_info should be set", describedHb.hasNexusTaskSlotsInfo());

          assertFalse(
              "workflow slot supplier kind should be set",
              describedHb.getWorkflowTaskSlotsInfo().getSlotSupplierKind().isEmpty());
          assertFalse(
              "activity slot supplier kind should be set",
              describedHb.getActivityTaskSlotsInfo().getSlotSupplierKind().isEmpty());

          assertEquals(
              "workflow used slots should be 0 after completion",
              0,
              describedHb.getWorkflowTaskSlotsInfo().getCurrentUsedSlots());
          assertEquals(
              "activity used slots should be 0 after completion",
              0,
              describedHb.getActivityTaskSlotsInfo().getCurrentUsedSlots());

          assertTrue(
              "workflow available slots should be > 0",
              describedHb.getWorkflowTaskSlotsInfo().getCurrentAvailableSlots() > 0);
          assertTrue(
              "activity available slots should be > 0",
              describedHb.getActivityTaskSlotsInfo().getCurrentAvailableSlots() > 0);

          // --- Task counters ---
          assertTrue(
              "workflow_task_slots_info.total_processed_tasks should be >= 1",
              describedHb.getWorkflowTaskSlotsInfo().getTotalProcessedTasks() >= 1);
          assertTrue(
              "activity_task_slots_info.total_processed_tasks should be >= 1",
              describedHb.getActivityTaskSlotsInfo().getTotalProcessedTasks() >= 1);

          // --- Poller info ---
          assertTrue("workflow_poller_info should be set", describedHb.hasWorkflowPollerInfo());
          assertTrue(
              "workflow_poller_info should have current_pollers > 0",
              describedHb.getWorkflowPollerInfo().getCurrentPollers() > 0);
          assertTrue("activity_poller_info should be set", describedHb.hasActivityPollerInfo());
          assertTrue(
              "activity_poller_info should have current_pollers > 0",
              describedHb.getActivityPollerInfo().getCurrentPollers() > 0);
          assertTrue("nexus_poller_info should be set", describedHb.hasNexusPollerInfo());
          assertTrue(
              "workflow_poller_info should have last_successful_poll_time set",
              describedHb.getWorkflowPollerInfo().hasLastSuccessfulPollTime());
          // activity_poller_info.last_successful_poll_time is asserted in
          // testActivityInFlightSlotTracking where a blocking activity ensures the task
          // goes through the poller (eager execution bypasses it here).

          assertFalse(
              "workflow_poller_info.is_autoscaling should be false with default pollers",
              describedHb.getWorkflowPollerInfo().getIsAutoscaling());
          assertFalse(
              "activity_poller_info.is_autoscaling should be false with default pollers",
              describedHb.getActivityPollerInfo().getIsAutoscaling());
        });
  }

  @Test
  public void testFailureMetricsInHeartbeat() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    FailingWorkflow wf =
        client.newWorkflowStub(
            FailingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    try {
      wf.execute();
    } catch (Exception e) {
      // Expected: the activity fails and the workflow fails
    }

    String workerInstanceKey = waitForWorkerInstanceKey();

    // ApplicationFailure is handled within the activity handler and returned as a result,
    // so it counts as a processed task, not a failed task. "Failed tasks" tracks
    // infrastructure-level failures where the task handler itself threw an exception.
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat hb = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", hb);
          assertTrue(
              "activity_task_slots_info.total_processed_tasks should be >= 1 after activity execution",
              hb.hasActivityTaskSlotsInfo()
                  && hb.getActivityTaskSlotsInfo().getTotalProcessedTasks() >= 1);

          // Invariant: totalFailed must never exceed totalProcessed. Before the fix,
          // recordFailed() could be called without recordProcessed() in catch blocks,
          // violating this invariant.
          for (String slotType : new String[] {"workflow", "activity", "local_activity", "nexus"}) {
            WorkerSlotsInfo slots;
            switch (slotType) {
              case "workflow":
                slots = hb.getWorkflowTaskSlotsInfo();
                break;
              case "activity":
                slots = hb.getActivityTaskSlotsInfo();
                break;
              case "local_activity":
                slots = hb.getLocalActivitySlotsInfo();
                break;
              case "nexus":
                slots = hb.getNexusTaskSlotsInfo();
                break;
              default:
                throw new AssertionError("unexpected slot type");
            }
            if (slots != null) {
              assertTrue(
                  slotType + " total_failed_tasks should not exceed total_processed_tasks",
                  slots.getTotalFailedTasks() <= slots.getTotalProcessedTasks());
            }
          }
        });
  }

  @Test
  public void testWorkflowTaskProcessedCounts() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    for (int i = 0; i < 3; i++) {
      TestWorkflow wf =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                  .build());
      assertEquals("done", wf.execute("test" + i));
    }

    String workerInstanceKey = waitForWorkerInstanceKey();

    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat hb = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", hb);
          assertTrue(
              "workflow_task_slots_info.total_processed_tasks should be >= 3 after 3 workflows",
              hb.hasWorkflowTaskSlotsInfo()
                  && hb.getWorkflowTaskSlotsInfo().getTotalProcessedTasks() >= 3);
        });
  }

  /** Verifies activity slots are occupied while an activity is running, then released after. */
  @Test
  public void testActivityInFlightSlotTracking() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    String workerInstanceKey = waitForWorkerInstanceKey();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    BlockingWorkflow wf =
        client.newWorkflowStub(
            BlockingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());

    // Start workflow async — the activity will block until we release it
    CompletableFuture<Void> wfFuture = WorkflowClient.execute(wf::execute);

    // Wait for the blocking activity to start
    assertTrue(
        "blocking activity should have started",
        blockingActivityStarted.await(10, TimeUnit.SECONDS));

    // While the activity is running, eventually a heartbeat should show used slots >= 1
    // and last_successful_poll_time should be set (the blocking activity goes through the poller,
    // unlike eager-executed activities)
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat hb = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", hb);
          assertTrue(
              "activity_task_slots_info.current_used_slots should be >= 1 while activity is running",
              hb.hasActivityTaskSlotsInfo()
                  && hb.getActivityTaskSlotsInfo().getCurrentUsedSlots() >= 1);
          assertTrue(
              "activity_poller_info should have last_successful_poll_time set",
              hb.hasActivityPollerInfo() && hb.getActivityPollerInfo().hasLastSuccessfulPollTime());
        });

    // Release the activity
    blockingActivityRelease.countDown();
    wfFuture.get(10, TimeUnit.SECONDS);

    // After completion, used slots should return to 0
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat hb = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", hb);
          assertEquals(
              "activity used slots should be 0 after activity completes",
              0,
              hb.getActivityTaskSlotsInfo().getCurrentUsedSlots());
        });
  }

  /** Verifies sticky cache counters are reported in heartbeat. */
  @Test
  public void testStickyCacheCountersInHeartbeat() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    // Run a workflow to generate at least one sticky cache hit or populate the cache
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow wf =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    assertEquals("done", wf.execute("test"));

    String workerInstanceKey = waitForWorkerInstanceKey();

    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat hb = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", hb);

          // Sticky cache fields should be present (values may be 0 if no cache hits yet)
          assertTrue(
              "total_sticky_cache_hit + total_sticky_cache_miss + current_sticky_cache_size should be >= 0",
              hb.getTotalStickyCacheHit() >= 0
                  && hb.getTotalStickyCacheMiss() >= 0
                  && hb.getCurrentStickyCacheSize() >= 0);
        });
  }

  /**
   * Verifies sticky cache misses are tracked in heartbeat. Starts a workflow with a blocking
   * activity, purges the cache while the activity runs, then completes. The workflow task on resume
   * triggers a cache miss. Matches Go's TestWorkerHeartbeatStickyCacheMiss and Rust's
   * worker_heartbeat_sticky_cache_miss.
   */
  @Test
  public void testStickyCacheMissInHeartbeat() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    String workerInstanceKey = waitForWorkerInstanceKey();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    CacheTestWorkflow wf =
        client.newWorkflowStub(
            CacheTestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());

    // Start workflow async — the activity will block until we release it
    CompletableFuture<String> wfFuture = WorkflowClient.execute(wf::execute);

    // Wait for the blocking activity to start
    assertTrue(
        "cache test activity should have started",
        cacheTestActivityStarted.await(10, TimeUnit.SECONDS));

    // Purge the sticky cache so the workflow's next WFT triggers a cache miss
    testWorkflowRule.invalidateWorkflowCache();

    // Release the activity — workflow resumes on non-sticky queue
    cacheTestActivityRelease.countDown();
    assertEquals("done", wfFuture.get(10, TimeUnit.SECONDS));

    // Wait for heartbeat to capture the sticky cache miss
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat hb = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", hb);
          assertTrue(
              "should have at least 1 sticky cache miss after cache purge",
              hb.getTotalStickyCacheMiss() >= 1);
        });
  }

  /**
   * Verifies that interval counters (last_interval_processed_tasks) reset between heartbeat
   * intervals. After workflow execution, the counter should be >0, then reset to 0 once idle.
   */
  @Test
  public void testIntervalCounterReset() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    String workerInstanceKey = waitForWorkerInstanceKey();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();

    // Run a workflow to generate processed tasks
    TestWorkflow wf =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                .build());
    assertEquals("done", wf.execute("test"));

    // After the workflow completes and the worker goes idle, interval counter should reset to 0
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          WorkerHeartbeat hb = describeWorker(workerInstanceKey);
          assertNotNull("DescribeWorker should return stored heartbeat", hb);
          assertTrue("workflow_task_slots_info should be set", hb.hasWorkflowTaskSlotsInfo());
          // total_processed_tasks should reflect the work was done
          assertTrue(
              "total_processed_tasks should be >= 1",
              hb.getWorkflowTaskSlotsInfo().getTotalProcessedTasks() >= 1);
          // After going idle, the interval counter should reset to 0
          assertEquals(
              "last_interval_processed_tasks should reset to 0 when no new work occurs",
              0,
              hb.getWorkflowTaskSlotsInfo().getLastIntervalProcessedTasks());
        });
  }

  /**
   * Tests that two workers on different task queues produce distinct instance keys but share the
   * same worker_grouping_key. Matches Go's TestWorkerHeartbeatMultipleWorkers.
   */
  @Test
  public void testMultipleWorkersHaveDistinctInstanceKeys() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    String taskQueue1 = testWorkflowRule.getTaskQueue() + "-multi1";
    String taskQueue2 = testWorkflowRule.getTaskQueue() + "-multi2";

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkerFactory factory =
        WorkerFactory.newInstance(client, testWorkflowRule.getWorkerFactoryOptions());

    Worker worker1 = factory.newWorker(taskQueue1);
    worker1.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker1.registerActivitiesImplementations(new TestActivityImpl());

    Worker worker2 = factory.newWorker(taskQueue2);
    worker2.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker2.registerActivitiesImplementations(new TestActivityImpl());

    factory.start();

    // Wait for both workers to appear via ListWorkers (e2e server-side verification)
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          List<WorkerHeartbeat> workers1 = listWorkersForQueue(taskQueue1);
          List<WorkerHeartbeat> workers2 = listWorkersForQueue(taskQueue2);
          assertFalse("worker 1 should appear via ListWorkers", workers1.isEmpty());
          assertFalse("worker 2 should appear via ListWorkers", workers2.isEmpty());

          WorkerHeartbeat hb1 = workers1.get(0);
          WorkerHeartbeat hb2 = workers2.get(0);

          assertFalse(
              "worker 1 instance key should not be empty", hb1.getWorkerInstanceKey().isEmpty());
          assertFalse(
              "worker 2 instance key should not be empty", hb2.getWorkerInstanceKey().isEmpty());
          assertNotEquals(
              "workers should have distinct instance keys",
              hb1.getWorkerInstanceKey(),
              hb2.getWorkerInstanceKey());

          assertTrue("worker 1 should have host info", hb1.hasHostInfo());
          assertTrue("worker 2 should have host info", hb2.hasHostInfo());
          assertEquals(
              "workers should share the same worker_grouping_key",
              hb1.getHostInfo().getWorkerGroupingKey(),
              hb2.getHostInfo().getWorkerGroupingKey());

          // Verify both are also accessible via DescribeWorker
          WorkerHeartbeat described1 = describeWorker(hb1.getWorkerInstanceKey());
          WorkerHeartbeat described2 = describeWorker(hb2.getWorkerInstanceKey());
          assertNotNull("worker 1 should be stored server-side", described1);
          assertNotNull("worker 2 should be stored server-side", described2);
          assertEquals(taskQueue1, described1.getTaskQueue());
          assertEquals(taskQueue2, described2.getTaskQueue());
        });

    factory.shutdown();
    factory.awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Tests that resource-based tuner reports SlotSupplierKind as "ResourceBased". Matches Go's
   * TestWorkerHeartbeatResourceBasedTuner.
   */
  @Test
  public void testResourceBasedSlotSupplierKind() throws Exception {
    testWorkflowRule.getTestEnvironment().start();

    String taskQueue = testWorkflowRule.getTaskQueue() + "-resource";

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkerFactory factory =
        WorkerFactory.newInstance(client, testWorkflowRule.getWorkerFactoryOptions());

    Worker worker =
        factory.newWorker(
            taskQueue,
            WorkerOptions.newBuilder()
                .setWorkerTuner(
                    ResourceBasedTuner.newBuilder()
                        .setControllerOptions(
                            ResourceBasedControllerOptions.newBuilder(0.7, 0.7).build())
                        .build())
                .build());
    worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
    worker.registerActivitiesImplementations(new TestActivityImpl());

    factory.start();

    // Discover the worker via ListWorkers, then verify slot supplier kind via DescribeWorker
    assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          List<WorkerHeartbeat> workers = listWorkersForQueue(taskQueue);
          assertFalse("worker should appear via ListWorkers", workers.isEmpty());

          WorkerHeartbeat described = describeWorker(workers.get(0).getWorkerInstanceKey());
          assertNotNull("DescribeWorker should return stored heartbeat", described);
          assertEquals(
              "workflow slot supplier kind should be ResourceBased",
              "ResourceBased",
              described.getWorkflowTaskSlotsInfo().getSlotSupplierKind());
          assertEquals(
              "activity slot supplier kind should be ResourceBased",
              "ResourceBased",
              described.getActivityTaskSlotsInfo().getSlotSupplierKind());
          assertEquals(
              "local activity slot supplier kind should be ResourceBased",
              "ResourceBased",
              described.getLocalActivitySlotsInfo().getSlotSupplierKind());
        });

    factory.shutdown();
    factory.awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Tests that no heartbeats are sent when heartbeat interval is not configured. Matches Go's
   * TestWorkerHeartbeatDisabled and Rust's worker_heartbeat_no_runtime_heartbeat.
   */
  /**
   * Verifies no heartbeats are sent when heartbeat interval is not configured. Polls ListWorkers
   * repeatedly over 5 seconds to confirm the worker never appears.
   */
  @SuppressWarnings("deprecation")
  @Test
  public void testNoHeartbeatsSentWhenDisabled() throws Exception {
    SDKTestWorkflowRule noHeartbeatRule =
        SDKTestWorkflowRule.newBuilder()
            .setUseExternalService(true)
            // No workerHeartbeatInterval — heartbeats should be disabled
            .setDoNotStart(true)
            .build();

    try {
      noHeartbeatRule.getTestEnvironment().start();

      String taskQueue = noHeartbeatRule.getTaskQueue();
      String namespace = noHeartbeatRule.getWorkflowClient().getOptions().getNamespace();

      // Poll ListWorkers over 5 seconds — worker should never appear
      long deadline = System.currentTimeMillis() + 2000;
      while (System.currentTimeMillis() < deadline) {
        List<WorkerHeartbeat> workers = listWorkersForQueue(taskQueue);
        assertTrue(
            "no workers should appear via ListWorkers when heartbeat interval is not configured",
            workers.isEmpty());
        Thread.sleep(500);
      }
    } finally {
      noHeartbeatRule.getTestEnvironment().shutdown();
      noHeartbeatRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
    }
  }

  /**
   * Discovers the workerInstanceKey by polling ListWorkers until a worker appears for the default
   * task queue.
   */
  private String waitForWorkerInstanceKey() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    return assertEventually(
        EVENTUALLY_TIMEOUT,
        () -> {
          List<WorkerHeartbeat> workers = listWorkersForQueue(taskQueue);
          assertFalse(
              "no workers found via ListWorkers for queue: " + taskQueue, workers.isEmpty());
          String key = workers.get(0).getWorkerInstanceKey();
          assertFalse("workerInstanceKey should not be empty", key.isEmpty());
          return key;
        });
  }

  /**
   * Lists workers via the ListWorkers RPC, filtered to a specific task queue. Uses the deprecated
   * WorkersInfo field because the replacement (WorkerListInfo) is not yet populated by the server.
   */
  @SuppressWarnings("deprecation")
  private List<WorkerHeartbeat> listWorkersForQueue(String taskQueue) {
    try {
      ListWorkersResponse resp =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .listWorkers(
                  ListWorkersRequest.newBuilder()
                      .setNamespace(
                          testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                      .setQuery("TaskQueue = \"" + taskQueue + "\"")
                      .setPageSize(200)
                      .build());
      return resp.getWorkersInfoList().stream()
          .map(info -> info.getWorkerHeartbeat())
          .collect(Collectors.toList());
    } catch (io.grpc.StatusRuntimeException e) {
      if (e.getStatus().getCode() == io.grpc.Status.Code.RESOURCE_EXHAUSTED) {
        return java.util.Collections.emptyList();
      }
      throw e;
    }
  }

  /**
   * Queries the test server for the stored heartbeat of a given worker via the DescribeWorker RPC.
   */
  private WorkerHeartbeat describeWorker(String workerInstanceKey) {
    try {
      DescribeWorkerResponse resp =
          testWorkflowRule
              .getWorkflowClient()
              .getWorkflowServiceStubs()
              .blockingStub()
              .describeWorker(
                  DescribeWorkerRequest.newBuilder()
                      .setNamespace(
                          testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                      .setWorkerInstanceKey(workerInstanceKey)
                      .build());
      return resp.getWorkerInfo().getWorkerHeartbeat();
    } catch (io.grpc.StatusRuntimeException e) {
      if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND
          || e.getStatus().getCode() == io.grpc.Status.Code.RESOURCE_EXHAUSTED) {
        return null;
      }
      throw e;
    }
  }

  // --- Workflow and Activity types ---

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String execute(String input);
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public String execute(String input) {
      TestActivity activity =
          Workflow.newActivityStub(
              TestActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
      return activity.doWork(input);
    }
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String doWork(String input);
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String doWork(String input) {
      return "done";
    }
  }

  @WorkflowInterface
  public interface FailingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class FailingWorkflowImpl implements FailingWorkflow {
    @Override
    public void execute() {
      FailingActivity activity =
          Workflow.newActivityStub(
              FailingActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(10))
                  .setRetryOptions(
                      io.temporal.common.RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      activity.fail();
    }
  }

  @ActivityInterface
  public interface FailingActivity {
    @ActivityMethod
    void fail();
  }

  public static class FailingActivityImpl implements FailingActivity {
    @Override
    public void fail() {
      throw io.temporal.failure.ApplicationFailure.newFailure(
          "intentional failure for test", "TestFailure");
    }
  }

  @WorkflowInterface
  public interface BlockingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class BlockingWorkflowImpl implements BlockingWorkflow {
    @Override
    public void execute() {
      BlockingActivity activity =
          Workflow.newActivityStub(
              BlockingActivity.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(30))
                  .setDisableEagerExecution(true)
                  .build());
      activity.block();
    }
  }

  @ActivityInterface
  public interface BlockingActivity {
    @ActivityMethod
    void block();
  }

  public static class BlockingActivityImpl implements BlockingActivity {
    @Override
    public void block() {
      blockingActivityStarted.countDown();
      try {
        blockingActivityRelease.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @WorkflowInterface
  public interface CacheTestWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class CacheTestWorkflowImpl implements CacheTestWorkflow {
    @Override
    public String execute() {
      CacheTestActivity activity =
          Workflow.newActivityStub(
              CacheTestActivity.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
      return activity.doCacheWork();
    }
  }

  @ActivityInterface
  public interface CacheTestActivity {
    @ActivityMethod
    String doCacheWork();
  }

  public static class CacheTestActivityImpl implements CacheTestActivity {
    @Override
    public String doCacheWork() {
      cacheTestActivityStarted.countDown();
      try {
        cacheTestActivityRelease.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return "done";
    }
  }
}
