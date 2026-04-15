package io.temporal.client.functional;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.enums.v1.ActivityIdConflictPolicy;
import io.temporal.api.enums.v1.ActivityIdReusePolicy;
import io.temporal.client.*;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.*;
import io.temporal.common.interceptors.ActivityClientCallsInterceptorBase;
import io.temporal.common.interceptors.ActivityClientInterceptor;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration tests for standalone activities started via {@link ActivityClient}. These tests are a
 * parallel to the .NET SDK's {@code TemporalClientActivityTests}.
 *
 * <p>All tests are gated behind {@link SDKTestWorkflowRule#useExternalService} because the embedded
 * test server may not support the standalone activity APIs.
 */
public class StandaloneActivityTest {

  // ---------------------------------------------------------------------------
  // Activity interfaces and implementations
  // ---------------------------------------------------------------------------

  @ActivityInterface
  public interface SimpleActivity {
    @ActivityMethod(name = "SimpleActivity")
    String execute(String input);
  }

  @ActivityInterface
  public interface VoidActivity {
    @ActivityMethod(name = "VoidActivity")
    void execute();
  }

  @ActivityInterface
  public interface WaitForCancelActivity {
    @ActivityMethod(name = "WaitForCancel")
    void waitForCancel();
  }

  @ActivityInterface
  public interface InspectInfoActivity {
    @ActivityMethod(name = "InspectInfo")
    ActivityInfoSnapshot inspectInfo();
  }

  /** Snapshot of {@link ActivityInfo} fields captured inside an activity body. */
  public static class ActivityInfoSnapshot {
    public String activityId;
    public String activityType;
    public String namespace;
    public String taskQueue;
    public boolean isLocal;
    public boolean isWorkflowActivity;
    public String workflowId;
    public String workflowRunId;
    public String workflowType;
  }

  public static class SimpleActivityImpl implements SimpleActivity {
    @Override
    public String execute(String input) {
      return "echo:" + input;
    }
  }

  public static class VoidActivityImpl implements VoidActivity {
    @Override
    public void execute() {}
  }

  /**
   * Static latch used by {@link WaitForCancelActivityImpl} to signal that it has started executing.
   * Set by the cancel/interceptor tests before starting the activity; cleared in {@code finally}.
   */
  private static volatile CountDownLatch cancelLatch;

  public static class WaitForCancelActivityImpl implements WaitForCancelActivity {
    @Override
    public void waitForCancel() {
      CountDownLatch latch = cancelLatch;
      if (latch != null) latch.countDown();
      while (true) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        // Throws ActivityCanceledException (→ CanceledFailure) when a cancel is requested.
        Activity.getExecutionContext().heartbeat(null);
      }
    }
  }

  public static class InspectInfoActivityImpl implements InspectInfoActivity {
    @Override
    public ActivityInfoSnapshot inspectInfo() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      ActivityInfoSnapshot snapshot = new ActivityInfoSnapshot();
      snapshot.activityId = info.getActivityId();
      snapshot.activityType = info.getActivityType();
      snapshot.namespace = info.getNamespace();
      snapshot.taskQueue = info.getActivityTaskQueue();
      snapshot.isLocal = info.isLocal();
      snapshot.isWorkflowActivity = info.isWorkflowActivity();
      snapshot.workflowId = info.getWorkflowId();
      snapshot.workflowRunId = info.getRunId();
      snapshot.workflowType = info.getWorkflowType();
      return snapshot;
    }
  }

  // ---------------------------------------------------------------------------
  // Test rule
  // ---------------------------------------------------------------------------

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setActivityImplementations(
              new SimpleActivityImpl(),
              new VoidActivityImpl(),
              new WaitForCancelActivityImpl(),
              new InspectInfoActivityImpl())
          .build();

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private String uniqueId() {
    return "act-" + UUID.randomUUID();
  }

  private StartActivityOptions simpleOpts(String id) {
    return StartActivityOptions.newBuilder()
        .setId(id)
        .setTaskQueue(testWorkflowRule.getTaskQueue())
        .setScheduleToCloseTimeout(Duration.ofMinutes(5))
        .build();
  }

  private ActivityClient newActivityClient() {
    return ActivityClient.newInstance(
        testWorkflowRule.getWorkflowServiceStubs(),
        ActivityClientOptions.newBuilder().setNamespace(SDKTestWorkflowRule.NAMESPACE).build());
  }

  // ---------------------------------------------------------------------------
  // Test 1: execute simple activity and get a typed result (.NET:
  // ExecuteActivityAsync_SimpleWithResult_Succeeds)
  // ---------------------------------------------------------------------------

  @Test
  public void testExecuteActivitySimpleWithResult() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    String result =
        newActivityClient()
            .execute("SimpleActivity", String.class, simpleOpts(uniqueId()), "hello");
    assertEquals("echo:hello", result);
  }

  // ---------------------------------------------------------------------------
  // Test 2: execute void activity (.NET: ExecuteActivityAsync_VoidResult_Succeeds)
  // ---------------------------------------------------------------------------

  @Test
  public void testExecuteActivityVoidResult() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    newActivityClient().execute("VoidActivity", simpleOpts(uniqueId()));
  }

  // ---------------------------------------------------------------------------
  // Test 3: execute activity by string type name (.NET: ExecuteActivityAsync_ByName_Succeeds)
  // ---------------------------------------------------------------------------

  @Test
  public void testExecuteActivityByName() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    String result =
        newActivityClient()
            .execute("SimpleActivity", String.class, simpleOpts(uniqueId()), "world");
    assertEquals("echo:world", result);
  }

  // ---------------------------------------------------------------------------
  // Test 4: starting an already-running activity with conflict policy Fail throws
  // (.NET: StartActivityAsync_AlreadyStarted_Throws)
  // ---------------------------------------------------------------------------

  @Test
  public void testStartActivityAlreadyStartedThrows() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    String activityId = uniqueId();
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(activityId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofMinutes(5))
            .setIdConflictPolicy(ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_FAIL)
            .build();

    UntypedActivityHandle handle = client.start("WaitForCancel", opts);
    try {
      ActivityAlreadyStartedException err =
          assertThrows(
              ActivityAlreadyStartedException.class, () -> client.start("WaitForCancel", opts));
      assertEquals(activityId, err.getActivityId());
      assertEquals("WaitForCancel", err.getActivityType());
      assertNotNull(err.getRunId());
    } finally {
      try {
        handle.terminate("test cleanup");
      } catch (Exception ignored) {
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Test 5: reject-duplicate ID reuse policy throws on re-start after completion
  // (.NET: StartActivityAsync_IdReusePolicyRejectDuplicate_Throws)
  // ---------------------------------------------------------------------------

  @Test
  public void testStartActivityIdReusePolicyRejectDuplicateThrows() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    String activityId = uniqueId();
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(activityId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofMinutes(5))
            .setIdReusePolicy(ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .build();

    // Start and wait for completion.
    UntypedActivityHandle first = client.start("SimpleActivity", opts, "first");
    first.getResult(String.class);

    // Attempting to start again with the same ID should throw.
    ActivityAlreadyStartedException err =
        assertThrows(
            ActivityAlreadyStartedException.class,
            () -> client.start("SimpleActivity", opts, "second"));
    assertEquals(activityId, err.getActivityId());
  }

  // ---------------------------------------------------------------------------
  // Test 6: get handle for a completed activity and read its result
  // (.NET: GetActivityHandle_ExistingActivity_Succeeds)
  // ---------------------------------------------------------------------------

  @Test
  public void testGetActivityHandleExistingActivitySucceeds() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    String activityId = uniqueId();

    UntypedActivityHandle handle = client.start("SimpleActivity", simpleOpts(activityId), "test");
    handle.getResult(String.class);

    // Reconstruct a handle from the known ID + runId.
    UntypedActivityHandle handle2 = client.getHandle(activityId, handle.getActivityRunId());
    assertEquals(activityId, handle2.getActivityId());
    assertEquals(handle.getActivityRunId(), handle2.getActivityRunId());
    assertEquals("echo:test", handle2.getResult(String.class));
  }

  // ---------------------------------------------------------------------------
  // Test 7: describe while running and after termination
  // (.NET: DescribeAsync_RunningAndTerminated_IsAccurate)
  // ---------------------------------------------------------------------------

  @Test
  public void testDescribeRunningAndTerminatedIsAccurate() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    String activityId = uniqueId();
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(activityId)
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofMinutes(5))
            .setStartToCloseTimeout(Duration.ofMinutes(5))
            .build();
    UntypedActivityHandle handle = client.start("WaitForCancel", opts);

    try {
      // Poll until we can confirm the activity is running.
      assertEventually(
          Duration.ofSeconds(30),
          () -> {
            ActivityExecutionDescription desc = handle.describe();
            assertEquals(
                ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING, desc.getStatus());
            assertEquals(activityId, desc.getActivityId());
            assertEquals("WaitForCancel", desc.getActivityType());
            assertEquals(testWorkflowRule.getTaskQueue(), desc.getTaskQueue());
            assertNotNull(desc.getScheduledTime());
            assertEquals(1, desc.getAttempt());
            assertNotNull(desc.getScheduleToCloseTimeout());
            assertNotNull(desc.getStartToCloseTimeout());
            assertNull(desc.getCloseTime());
          });

      handle.terminate("test cleanup");

      // Poll until the server reflects the terminated status.
      assertEventually(
          Duration.ofSeconds(30),
          () -> {
            ActivityExecutionDescription desc = handle.describe();
            assertEquals(
                ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_TERMINATED, desc.getStatus());
            assertNotNull(desc.getCloseTime());
          });
    } finally {
      try {
        handle.terminate("test cleanup");
      } catch (Exception ignored) {
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Test 8: user metadata (staticSummary / staticDetails) is preserved in describe
  // (.NET: DescribeAsync_UserMetadata_IsAccurate)
  // ---------------------------------------------------------------------------

  @Test
  public void testDescribeUserMetadataIsAccurate() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    StartActivityOptions opts =
        StartActivityOptions.newBuilder()
            .setId(uniqueId())
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setScheduleToCloseTimeout(Duration.ofMinutes(5))
            .setStaticSummary("Test summary")
            .setStaticDetails("Test details\nLine 2")
            .build();

    UntypedActivityHandle handle = client.start("SimpleActivity", opts, "meta");
    handle.getResult(String.class);

    ActivityExecutionDescription desc = handle.describe();
    assertEquals("Test summary", desc.getStaticSummary());
    assertEquals("Test details\nLine 2", desc.getStaticDetails());
  }

  // ---------------------------------------------------------------------------
  // Test 9: cancel a running activity; getResult throws ActivityFailedException(CanceledFailure)
  // (.NET: CancelAsync_RunningActivity_Succeeds)
  // ---------------------------------------------------------------------------

  @Test
  public void testCancelRunningActivitySucceeds() throws InterruptedException {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    cancelLatch = new CountDownLatch(1);
    try {
      ActivityClient client = newActivityClient();
      UntypedActivityHandle handle =
          client.start(
              "WaitForCancel",
              StartActivityOptions.newBuilder()
                  .setId(uniqueId())
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                  .setHeartbeatTimeout(Duration.ofSeconds(10))
                  .build());

      // Wait for the activity to start executing and reach its heartbeat loop.
      assertTrue("Activity did not start within 30s", cancelLatch.await(30, TimeUnit.SECONDS));

      // Request cancellation.
      handle.cancel(ActivityCancelOptions.newBuilder().setReason("test cancel reason").build());

      // getResult must throw ActivityFailedException wrapping CanceledFailure.
      ActivityFailedException err =
          assertThrows(ActivityFailedException.class, () -> handle.getResult(Void.class));
      assertThat(err.getCause(), instanceOf(CanceledFailure.class));

      // Describe should eventually show the canceled status.
      assertEventually(
          Duration.ofSeconds(30),
          () -> {
            ActivityExecutionDescription desc = handle.describe();
            assertEquals(
                ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_CANCELED, desc.getStatus());
          });
    } finally {
      cancelLatch = null;
    }
  }

  // ---------------------------------------------------------------------------
  // Test 10: list, count, and paginate activities
  // (.NET: ListActivitiesAsync_SimpleList_IsAccurate)
  // ---------------------------------------------------------------------------

  @Test
  public void testListActivitiesSimpleListIsAccurate() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    String taskQueue = testWorkflowRule.getTaskQueue();

    // Complete 5 activities sequentially.
    for (int i = 0; i < 5; i++) {
      client.execute(
          "SimpleActivity",
          String.class,
          StartActivityOptions.newBuilder()
              .setId("act-list-" + UUID.randomUUID())
              .setTaskQueue(taskQueue)
              .setScheduleToCloseTimeout(Duration.ofMinutes(5))
              .build(),
          "item-" + i);
    }

    String query = "TaskQueue = '" + taskQueue + "'";

    // Verify the list stream.
    assertEventually(
        Duration.ofSeconds(30),
        () -> {
          List<ActivityExecution> activities =
              client.listExecutions(query).collect(Collectors.toList());
          assertEquals(5, activities.size());
          for (ActivityExecution act : activities) {
            assertEquals("SimpleActivity", act.getActivityType());
            assertEquals(taskQueue, act.getTaskQueue());
            assertEquals(
                ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_COMPLETED, act.getStatus());
          }
        });

    // Verify count.
    assertEventually(
        Duration.ofSeconds(30),
        () -> {
          ActivityExecutionCount count = client.countExecutions(query);
          assertEquals(5, count.getCount());
        });

    // Verify manual pagination: page size 2 → 3 pages (2 + 2 + 1).
    assertEventually(
        Duration.ofSeconds(30),
        () -> {
          ActivityListPaginatedOptions pageOpts =
              ActivityListPaginatedOptions.newBuilder().setPageSize(2).build();

          ActivityListPage first = client.listExecutionsPaginated(query, null, pageOpts);
          assertEquals(2, first.getActivities().size());
          assertNotNull(first.getNextPageToken());

          ActivityListPage second =
              client.listExecutionsPaginated(query, first.getNextPageToken(), pageOpts);
          assertEquals(2, second.getActivities().size());
          assertNotNull(second.getNextPageToken());

          ActivityListPage third =
              client.listExecutionsPaginated(query, second.getNextPageToken(), pageOpts);
          assertEquals(1, third.getActivities().size());
          assertNull(third.getNextPageToken());
        });
  }

  // ---------------------------------------------------------------------------
  // Test 11: interceptors are invoked for all activity operations
  // (.NET: StartActivityAsync_Interceptors_AreCalledProperly)
  // ---------------------------------------------------------------------------

  @Test
  public void testStartActivityInterceptorsAreCalledProperly() throws InterruptedException {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    cancelLatch = new CountDownLatch(1);
    try {
      ActivityTracingInterceptor interceptor = new ActivityTracingInterceptor();
      ActivityClient interceptedClient =
          ActivityClient.newInstance(
              testWorkflowRule.getWorkflowServiceStubs(),
              ActivityClientOptions.newBuilder()
                  .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                  .setInterceptors(Collections.singletonList(interceptor))
                  .build());

      String activityId = uniqueId();
      UntypedActivityHandle handle =
          interceptedClient.start(
              "WaitForCancel",
              StartActivityOptions.newBuilder()
                  .setId(activityId)
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                  .build());

      // Wait for activity to start, then poll describe until running.
      assertTrue("Activity did not start within 30s", cancelLatch.await(30, TimeUnit.SECONDS));
      assertEventually(
          Duration.ofSeconds(30),
          () -> {
            ActivityExecutionDescription desc = handle.describe();
            assertEquals(
                ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING, desc.getStatus());
          });

      handle.cancel();
      handle.terminate("test cleanup");

      List<String> events = interceptor.events;
      assertEquals("startActivity should be first", "startActivity", events.get(0));
      assertTrue("describeActivity should be recorded", events.contains("describeActivity"));

      int cancelIdx = events.lastIndexOf("cancelActivity");
      int terminateIdx = events.lastIndexOf("terminateActivity");
      assertTrue("cancelActivity should be recorded", cancelIdx >= 0);
      assertTrue("terminateActivity should be recorded", terminateIdx >= 0);
      assertTrue("cancelActivity should precede terminateActivity", cancelIdx < terminateIdx);
    } finally {
      cancelLatch = null;
    }
  }

  // ---------------------------------------------------------------------------
  // Test 12: ActivityInfo inside the activity body reflects standalone context
  // (.NET: ExecuteActivityAsync_WorkerActivityInfo_IsAccurate)
  // ---------------------------------------------------------------------------

  @Test
  public void testExecuteActivityWorkerActivityInfoIsAccurate() {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    String activityId = uniqueId();
    ActivityInfoSnapshot info =
        newActivityClient()
            .execute("InspectInfo", ActivityInfoSnapshot.class, simpleOpts(activityId));

    assertEquals(activityId, info.activityId);
    assertEquals("InspectInfo", info.activityType);
    assertEquals(SDKTestWorkflowRule.NAMESPACE, info.namespace);
    assertEquals(testWorkflowRule.getTaskQueue(), info.taskQueue);
    assertFalse(info.isLocal);
    assertFalse(info.isWorkflowActivity);
    assertNull(info.workflowId);
    assertNull(info.workflowRunId);
    assertNull(info.workflowType);
  }

  // ---------------------------------------------------------------------------
  // Test 13: executeAsync returns a CompletableFuture that resolves to the typed result
  // ---------------------------------------------------------------------------

  @Test
  public void testExecuteAsyncReturnsResult() throws Exception {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    CompletableFuture<String> future =
        newActivityClient()
            .executeAsync("SimpleActivity", String.class, simpleOpts(uniqueId()), "hello");
    assertEquals("echo:hello", future.get());
  }

  // ---------------------------------------------------------------------------
  // Test 14: getResultAsync on an UntypedActivityHandle returns a resolved future
  // ---------------------------------------------------------------------------

  @Test
  public void testGetResultAsyncOnHandle() throws Exception {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    UntypedActivityHandle handle =
        newActivityClient().start("SimpleActivity", simpleOpts(uniqueId()), "world");
    CompletableFuture<String> future = handle.getResultAsync(String.class);
    assertEquals("echo:world", future.get());
  }

  // ---------------------------------------------------------------------------
  // Test 15: typed ActivityHandle<R>.getResult() no-arg path
  // ---------------------------------------------------------------------------

  @Test
  public void testTypedHandleGetResultNoArg() throws ActivityFailedException {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityHandle<String> handle =
        newActivityClient().start("SimpleActivity", String.class, simpleOpts(uniqueId()), "typed");
    assertEquals("echo:typed", handle.getResult());
  }

  // ---------------------------------------------------------------------------
  // Test 16: client.getHandle(id, runId, Class<R>) returns a typed ActivityHandle<R>
  // ---------------------------------------------------------------------------

  @Test
  public void testGetHandleTypedReturnsActivityHandleR() throws ActivityFailedException {
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    ActivityClient client = newActivityClient();
    String activityId = uniqueId();
    UntypedActivityHandle started = client.start("SimpleActivity", simpleOpts(activityId), "typed");
    String runId = started.getActivityRunId();
    started.getResult(String.class); // wait for completion

    ActivityHandle<String> typed = client.getHandle(activityId, runId, String.class);
    assertEquals("echo:typed", typed.getResult());
  }

  // ---------------------------------------------------------------------------
  // Interceptor helpers
  // ---------------------------------------------------------------------------

  static class ActivityTracingInterceptor implements ActivityClientInterceptor {
    final List<String> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public ActivityClientCallsInterceptor activityClientCallsInterceptor(
        ActivityClientCallsInterceptor next) {
      return new ActivityClientCallsInterceptorBase(next) {
        @Override
        public StartActivityOutput startActivity(StartActivityInput input) {
          events.add("startActivity");
          return super.startActivity(input);
        }

        @Override
        public <R> GetActivityResultOutput<R> getActivityResult(GetActivityResultInput<R> input)
            throws ActivityFailedException {
          events.add("getActivityResult");
          return super.getActivityResult(input);
        }

        @Override
        public DescribeActivityOutput describeActivity(DescribeActivityInput input) {
          events.add("describeActivity");
          return super.describeActivity(input);
        }

        @Override
        public CancelActivityOutput cancelActivity(CancelActivityInput input) {
          events.add("cancelActivity");
          return super.cancelActivity(input);
        }

        @Override
        public TerminateActivityOutput terminateActivity(TerminateActivityInput input) {
          events.add("terminateActivity");
          return super.terminateActivity(input);
        }
      };
    }
  }
}
