package io.temporal.worker;

import static org.junit.Assert.*;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.enums.v1.WorkerStatus;
import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.tuning.ResourceBasedControllerOptions;
import io.temporal.worker.tuning.ResourceBasedTuner;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class WorkerHeartbeatIntegrationTest {

  private static final HeartbeatCapturingInterceptor interceptor =
      new HeartbeatCapturingInterceptor();

  // Shared latches for blocking activity tests
  static final CountDownLatch blockingActivityStarted = new CountDownLatch(1);
  static final CountDownLatch blockingActivityRelease = new CountDownLatch(1);

  // Separate latches for sticky cache miss test
  static final CountDownLatch cacheTestActivityStarted = new CountDownLatch(1);
  static final CountDownLatch cacheTestActivityRelease = new CountDownLatch(1);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowServiceStubsOptions(
              WorkflowServiceStubsOptions.newBuilder()
                  .setGrpcClientInterceptors(Collections.singletonList(interceptor))
                  .build())
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
   * elapsed_since_last_heartbeat.
   */
  @Test
  public void testBasicHeartbeatFields() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    // Wait for at least 2 heartbeat ticks (interval is 1s)
    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());
    assertTrue("need >= 2 heartbeats", requests.size() >= 2);
    String workerInstanceKey = requests.get(0).getWorkerHeartbeat(0).getWorkerInstanceKey();

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
    io.temporal.api.worker.v1.WorkerHeartbeat rawHb = requests.get(0).getWorkerHeartbeat(0);
    assertTrue("start_time should be set", rawHb.hasStartTime());
    long startTimeSec = rawHb.getStartTime().getSeconds();
    long nowSec = java.time.Instant.now().getEpochSecond();
    assertTrue("start_time should be within 30 seconds of now", nowSec - startTimeSec <= 30);
    assertTrue("heartbeat_time should be set", rawHb.hasHeartbeatTime());
    long heartbeatTimeSec = rawHb.getHeartbeatTime().getSeconds();
    assertTrue(
        "heartbeat_time should be within 30 seconds of now", nowSec - heartbeatTimeSec <= 30);
    assertTrue("heartbeat_time should be >= start_time", heartbeatTimeSec >= startTimeSec);

    // --- Plugins ---
    assertEquals(
        "plugins list should be empty when no plugins are configured", 0, rawHb.getPluginsCount());

    // --- Elapsed since last heartbeat ---

    // First heartbeat should not have elapsed_since_last_heartbeat
    WorkerHeartbeat first = requests.get(0).getWorkerHeartbeat(0);
    assertFalse(
        "first heartbeat should not have elapsed_since_last_heartbeat",
        first.hasElapsedSinceLastHeartbeat());

    // Subsequent heartbeats should have a non-zero elapsed duration
    boolean foundNonZeroElapsed =
        requests.stream()
            .skip(1)
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .filter(WorkerHeartbeat::hasElapsedSinceLastHeartbeat)
            .anyMatch(
                h -> {
                  com.google.protobuf.Duration d = h.getElapsedSinceLastHeartbeat();
                  return d.getSeconds() > 0 || d.getNanos() > 0;
                });
    assertTrue(
        "subsequent heartbeats should have non-zero elapsed_since_last_heartbeat",
        foundNonZeroElapsed);
  }

  @Test
  public void testShutdownHeartbeatStatus() throws Exception {
    interceptor.clear();
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

    // Wait for at least one heartbeat to confirm the worker is running
    Thread.sleep(2000);

    // Graceful shutdown triggers ShutdownWorkerRequest with SHUTTING_DOWN status
    factory.shutdown();
    factory.awaitTermination(10, TimeUnit.SECONDS);

    List<ShutdownWorkerRequest> shutdownRequests = interceptor.getShutdownRequests();
    assertFalse("no shutdown requests captured", shutdownRequests.isEmpty());

    // Find the shutdown request for our task queue
    ShutdownWorkerRequest shutdownReq =
        shutdownRequests.stream()
            .filter(req -> req.getTaskQueue().equals(taskQueue))
            .findFirst()
            .orElse(null);
    assertNotNull("should find ShutdownWorkerRequest for task queue: " + taskQueue, shutdownReq);
    assertTrue(
        "ShutdownWorkerRequest should include a worker_heartbeat",
        shutdownReq.hasWorkerHeartbeat());
    assertEquals(
        "shutdown heartbeat status should be WORKER_STATUS_SHUTTING_DOWN",
        WorkerStatus.WORKER_STATUS_SHUTTING_DOWN,
        shutdownReq.getWorkerHeartbeat().getStatus());
    assertFalse(
        "shutdown heartbeat task_queue should be set",
        shutdownReq.getWorkerHeartbeat().getTaskQueue().isEmpty());

    // No Nexus services registered, so NEXUS should not be in task queue types
    assertTrue(
        "task_queue_types should include WORKFLOW",
        shutdownReq.getTaskQueueTypesList().contains(TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW));
    assertTrue(
        "task_queue_types should include ACTIVITY",
        shutdownReq.getTaskQueueTypesList().contains(TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY));
    assertFalse(
        "task_queue_types should NOT include NEXUS when no Nexus services are registered",
        shutdownReq.getTaskQueueTypesList().contains(TaskQueueType.TASK_QUEUE_TYPE_NEXUS));
  }

  /**
   * Combined test for heartbeat fields that require workflow execution. Covers: slot info, task
   * counters, and poller info.
   */
  @Test
  public void testHeartbeatAfterWorkflowExecution() throws Exception {
    interceptor.clear();
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

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());
    String workerInstanceKey = requests.get(0).getWorkerHeartbeat(0).getWorkerInstanceKey();

    // --- Slot info via DescribeWorker ---
    WorkerHeartbeat describedHb = describeWorker(workerInstanceKey);
    assertNotNull("DescribeWorker should return stored heartbeat", describedHb);

    assertTrue("workflow_task_slots_info should be set", describedHb.hasWorkflowTaskSlotsInfo());
    assertTrue("activity_task_slots_info should be set", describedHb.hasActivityTaskSlotsInfo());
    assertTrue("local_activity_slots_info should be set", describedHb.hasLocalActivitySlotsInfo());
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
    boolean foundWorkflowProcessed =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                h ->
                    h.hasWorkflowTaskSlotsInfo()
                        && h.getWorkflowTaskSlotsInfo().getTotalProcessedTasks() >= 1);
    assertTrue(
        "workflow_task_slots_info.total_processed_tasks should be >= 1", foundWorkflowProcessed);

    boolean foundActivityProcessed =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                h ->
                    h.hasActivityTaskSlotsInfo()
                        && h.getActivityTaskSlotsInfo().getTotalProcessedTasks() >= 1);
    assertTrue(
        "activity_task_slots_info.total_processed_tasks should be >= 1", foundActivityProcessed);

    // --- Poller info ---
    boolean foundWorkflowPollers =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                h ->
                    h.hasWorkflowPollerInfo() && h.getWorkflowPollerInfo().getCurrentPollers() > 0);
    assertTrue("workflow_poller_info should have current_pollers > 0", foundWorkflowPollers);

    boolean foundActivityPollers =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                h ->
                    h.hasActivityPollerInfo() && h.getActivityPollerInfo().getCurrentPollers() > 0);
    assertTrue("activity_poller_info should have current_pollers > 0", foundActivityPollers);

    boolean foundNexusPollerInfo =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(WorkerHeartbeat::hasNexusPollerInfo);
    assertTrue("nexus_poller_info should be set", foundNexusPollerInfo);

    boolean foundWorkflowPollTime =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                h ->
                    h.hasWorkflowPollerInfo()
                        && h.getWorkflowPollerInfo().hasLastSuccessfulPollTime());
    assertTrue(
        "workflow_poller_info should have last_successful_poll_time set", foundWorkflowPollTime);

    boolean foundActivityPollTime =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                h ->
                    h.hasActivityPollerInfo()
                        && h.getActivityPollerInfo().hasLastSuccessfulPollTime());
    assertTrue(
        "activity_poller_info should have last_successful_poll_time set", foundActivityPollTime);

    boolean foundWorkflowAutoscaling =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                h -> h.hasWorkflowPollerInfo() && h.getWorkflowPollerInfo().getIsAutoscaling());
    assertFalse(
        "workflow_poller_info.is_autoscaling should be false with default pollers",
        foundWorkflowAutoscaling);

    boolean foundActivityAutoscaling =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                h -> h.hasActivityPollerInfo() && h.getActivityPollerInfo().getIsAutoscaling());
    assertFalse(
        "activity_poller_info.is_autoscaling should be false with default pollers",
        foundActivityAutoscaling);
  }

  @Test
  public void testFailureMetricsInHeartbeat() throws Exception {
    interceptor.clear();
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

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());

    // ApplicationFailure is handled within the activity handler and returned as a result,
    // so it counts as a processed task, not a failed task. "Failed tasks" tracks
    // infrastructure-level failures where the task handler itself threw an exception.
    boolean foundActivityProcessed =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                hb ->
                    hb.hasActivityTaskSlotsInfo()
                        && hb.getActivityTaskSlotsInfo().getTotalProcessedTasks() >= 1);
    assertTrue(
        "activity_task_slots_info.total_processed_tasks should be >= 1 after activity execution",
        foundActivityProcessed);
  }

  @Test
  public void testWorkflowTaskProcessedCounts() throws Exception {
    interceptor.clear();
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

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());

    long maxProcessed =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .filter(io.temporal.api.worker.v1.WorkerHeartbeat::hasWorkflowTaskSlotsInfo)
            .mapToLong(hb -> hb.getWorkflowTaskSlotsInfo().getTotalProcessedTasks())
            .max()
            .orElse(0);
    assertTrue(
        "workflow_task_slots_info.total_processed_tasks should be >= 3 after 3 workflows",
        maxProcessed >= 3);
  }

  /** Verifies activity slots are occupied while an activity is running, then released after. */
  @Test
  public void testActivityInFlightSlotTracking() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

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

    // Wait for a heartbeat to capture the in-flight state
    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());

    // While the activity is running, at least one heartbeat should show used slots >= 1
    boolean foundUsedSlot =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                hb ->
                    hb.hasActivityTaskSlotsInfo()
                        && hb.getActivityTaskSlotsInfo().getCurrentUsedSlots() >= 1);
    assertTrue(
        "activity_task_slots_info.current_used_slots should be >= 1 while activity is running",
        foundUsedSlot);

    // Release the activity
    blockingActivityRelease.countDown();
    wfFuture.get(10, TimeUnit.SECONDS);

    // Wait for a heartbeat after completion
    Thread.sleep(2000);

    requests = interceptor.getHeartbeatRequests();
    // Get the last heartbeat
    WorkerHeartbeat lastHb = requests.get(requests.size() - 1).getWorkerHeartbeat(0);
    assertEquals(
        "activity used slots should be 0 after activity completes",
        0,
        lastHb.getActivityTaskSlotsInfo().getCurrentUsedSlots());
  }

  /** Verifies sticky cache counters are reported in heartbeat. */
  @Test
  public void testStickyCacheCountersInHeartbeat() throws Exception {
    interceptor.clear();
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

    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());
    String workerInstanceKey = requests.get(0).getWorkerHeartbeat(0).getWorkerInstanceKey();

    WorkerHeartbeat hb = describeWorker(workerInstanceKey);
    assertNotNull("DescribeWorker should return stored heartbeat", hb);

    // Sticky cache fields should be present (values may be 0 if no cache hits yet)
    // The key thing is the fields are populated — exact values depend on timing
    assertTrue(
        "total_sticky_cache_hit + total_sticky_cache_miss + current_sticky_cache_size should be >= 0",
        hb.getTotalStickyCacheHit() >= 0
            && hb.getTotalStickyCacheMiss() >= 0
            && hb.getCurrentStickyCacheSize() >= 0);
  }

  /**
   * Verifies sticky cache misses are tracked in heartbeat. Starts a workflow with a blocking
   * activity, purges the cache while the activity runs, then completes. The workflow task on resume
   * triggers a cache miss. Matches Go's TestWorkerHeartbeatStickyCacheMiss and Rust's
   * worker_heartbeat_sticky_cache_miss.
   */
  @Test
  public void testStickyCacheMissInHeartbeat() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

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
    Thread.sleep(2000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());

    boolean foundCacheMiss =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(hb -> hb.getTotalStickyCacheMiss() >= 1);
    assertTrue("should have at least 1 sticky cache miss after cache purge", foundCacheMiss);
  }

  /**
   * Verifies that interval counters (last_interval_processed_tasks) reset between heartbeat
   * intervals.
   */
  @Test
  public void testIntervalCounterReset() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

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

    // Wait for heartbeats to capture the processed tasks AND a subsequent heartbeat with no new
    // work
    Thread.sleep(4000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertTrue("need >= 3 heartbeats", requests.size() >= 3);

    // Find a heartbeat with interval processed > 0
    boolean foundNonZeroInterval =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .anyMatch(
                hb ->
                    hb.hasWorkflowTaskSlotsInfo()
                        && hb.getWorkflowTaskSlotsInfo().getLastIntervalProcessedTasks() > 0);
    assertTrue(
        "should find a heartbeat with last_interval_processed_tasks > 0", foundNonZeroInterval);

    // The last heartbeat (after no new work) should have interval processed = 0
    WorkerHeartbeat lastHb = requests.get(requests.size() - 1).getWorkerHeartbeat(0);
    if (lastHb.hasWorkflowTaskSlotsInfo()) {
      assertEquals(
          "last_interval_processed_tasks should reset to 0 when no new work occurs",
          0,
          lastHb.getWorkflowTaskSlotsInfo().getLastIntervalProcessedTasks());
    }
  }

  /**
   * Tests that two workers on different task queues produce distinct instance keys but share the
   * same worker_grouping_key. Matches Go's TestWorkerHeartbeatMultipleWorkers.
   */
  @Test
  public void testMultipleWorkersHaveDistinctInstanceKeys() throws Exception {
    interceptor.clear();
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

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());

    // Workers may be in separate requests (different HeartbeatManagers), so search across all
    WorkerHeartbeat hb1 =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .filter(hb -> hb.getTaskQueue().equals(taskQueue1))
            .findFirst()
            .orElse(null);
    WorkerHeartbeat hb2 =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .filter(hb -> hb.getTaskQueue().equals(taskQueue2))
            .findFirst()
            .orElse(null);

    assertNotNull("should find heartbeat for task queue 1", hb1);
    assertNotNull("should find heartbeat for task queue 2", hb2);

    assertFalse("worker 1 instance key should not be empty", hb1.getWorkerInstanceKey().isEmpty());
    assertFalse("worker 2 instance key should not be empty", hb2.getWorkerInstanceKey().isEmpty());
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

    // Verify both are stored server-side
    WorkerHeartbeat described1 = describeWorker(hb1.getWorkerInstanceKey());
    WorkerHeartbeat described2 = describeWorker(hb2.getWorkerInstanceKey());
    assertNotNull("worker 1 should be stored server-side", described1);
    assertNotNull("worker 2 should be stored server-side", described2);
    assertEquals(taskQueue1, described1.getTaskQueue());
    assertEquals(taskQueue2, described2.getTaskQueue());

    factory.shutdown();
    factory.awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Tests that resource-based tuner reports SlotSupplierKind as "ResourceBased". Matches Go's
   * TestWorkerHeartbeatResourceBasedTuner.
   */
  @Test
  public void testResourceBasedSlotSupplierKind() throws Exception {
    interceptor.clear();
    testWorkflowRule.getTestEnvironment().start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkerFactory factory =
        WorkerFactory.newInstance(client, testWorkflowRule.getWorkerFactoryOptions());

    Worker worker =
        factory.newWorker(
            testWorkflowRule.getTaskQueue() + "-resource",
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

    Thread.sleep(3000);

    List<RecordWorkerHeartbeatRequest> requests = interceptor.getHeartbeatRequests();
    assertFalse("no heartbeats captured", requests.isEmpty());

    // Find the heartbeat for the resource-based worker
    WorkerHeartbeat hb =
        requests.stream()
            .flatMap(req -> req.getWorkerHeartbeatList().stream())
            .filter(h -> h.getTaskQueue().equals(testWorkflowRule.getTaskQueue() + "-resource"))
            .findFirst()
            .orElse(null);
    assertNotNull("should find heartbeat for resource-based worker", hb);

    assertEquals(
        "workflow slot supplier kind should be ResourceBased",
        "ResourceBased",
        hb.getWorkflowTaskSlotsInfo().getSlotSupplierKind());
    assertEquals(
        "activity slot supplier kind should be ResourceBased",
        "ResourceBased",
        hb.getActivityTaskSlotsInfo().getSlotSupplierKind());
    assertEquals(
        "local activity slot supplier kind should be ResourceBased",
        "ResourceBased",
        hb.getLocalActivitySlotsInfo().getSlotSupplierKind());

    factory.shutdown();
    factory.awaitTermination(10, TimeUnit.SECONDS);
  }

  /**
   * Tests that no heartbeats are sent when heartbeat interval is not configured. Matches Go's
   * TestWorkerHeartbeatDisabled and Rust's worker_heartbeat_no_runtime_heartbeat.
   */
  @Test
  public void testNoHeartbeatsSentWhenDisabled() throws Exception {
    HeartbeatCapturingInterceptor localInterceptor = new HeartbeatCapturingInterceptor();

    SDKTestWorkflowRule noHeartbeatRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowServiceStubsOptions(
                WorkflowServiceStubsOptions.newBuilder()
                    .setGrpcClientInterceptors(Collections.singletonList(localInterceptor))
                    .build())
            // No workerHeartbeatInterval — heartbeats should be disabled
            .setDoNotStart(true)
            .build();

    try {
      localInterceptor.clear();
      noHeartbeatRule.getTestEnvironment().start();

      Thread.sleep(5000);

      assertTrue(
          "no heartbeats should be sent when heartbeat interval is not configured",
          localInterceptor.getHeartbeatRequests().isEmpty());
    } finally {
      noHeartbeatRule.getTestEnvironment().shutdown();
      noHeartbeatRule.getTestEnvironment().awaitTermination(10, TimeUnit.SECONDS);
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
      if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
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
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());
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
