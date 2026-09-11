package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.reflect.TypeToken;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ActivityInvocationOptions;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ActivityInvocationIdTest {
  private static final Duration ACTIVITY_TIMEOUT = Duration.ofSeconds(5);
  private static final ActivityOptions ACTIVITY_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(ACTIVITY_TIMEOUT).build();
  private static final LocalActivityOptions LOCAL_ACTIVITY_OPTIONS =
      LocalActivityOptions.newBuilder().setStartToCloseTimeout(ACTIVITY_TIMEOUT).build();
  private static final InvocationIdActivitiesImpl ACTIVITIES = new InvocationIdActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowImpl.class, GenericWorkflowImpl.class)
          .setActivityImplementations(ACTIVITIES)
          .build();

  @Before
  public void setUp() {
    TestWorkflowImpl.configuredActivityId = "replay-activity";
  }

  @Test
  public void typedExecuteActivityRecordsSuppliedIdInActivityInfoAndHistory() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> result = workflow.execute(Invocation.TYPED_SYNC, "typed-sync-activity");

    assertEquals(Collections.singletonList("typed-sync-activity"), result);
    assertEquals(result, scheduledActivityIds(workflow));
  }

  @Test
  public void typedExecuteActivityAsyncPreservesGenericReturnType() {
    GenericWorkflow workflow = newWorkflow(GenericWorkflow.class);
    List<UUID> values = Arrays.asList(UUID.randomUUID(), UUID.randomUUID());

    List<UUID> result =
        workflow.execute(GenericInvocation.TYPED_ASYNC, values, "typed-async-generic");

    assertEquals(values, result);
    assertEquals(Collections.singletonList("typed-async-generic"), scheduledActivityIds(workflow));
  }

  @Test
  public void untypedExecuteOverloadSupportsGenericResultType() {
    GenericWorkflow workflow = newWorkflow(GenericWorkflow.class);
    List<UUID> values = Arrays.asList(UUID.randomUUID(), UUID.randomUUID());

    List<UUID> result = workflow.execute(GenericInvocation.UNTYPED_SYNC, values, "untyped-generic");

    assertEquals(values, result);
    assertEquals(Collections.singletonList("untyped-generic"), scheduledActivityIds(workflow));
  }

  @Test
  public void concurrentTypedInvocationsUsingSameStubKeepDistinctIds() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> result =
        workflow.execute(
            Invocation.CONCURRENT_TYPED, "concurrent-activity-a", "concurrent-activity-b");

    assertEquals(Arrays.asList("concurrent-activity-a", "concurrent-activity-b"), result);
    assertEquals(result, scheduledActivityIds(workflow));
  }

  @Test
  public void concurrentUntypedInvocationsUsingSameStubKeepDistinctIds() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> result =
        workflow.execute(
            Invocation.CONCURRENT_UNTYPED, "untyped-concurrent-a", "untyped-concurrent-b");

    assertEquals(Arrays.asList("untyped-concurrent-a", "untyped-concurrent-b"), result);
    assertEquals(result, scheduledActivityIds(workflow));
  }

  @Test
  public void typedVoidActivityUsesSuppliedId() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> result = workflow.execute(Invocation.TYPED_VOID, "typed-void-activity");

    assertEquals(Collections.singletonList("typed-void-activity"), result);
    assertEquals(result, scheduledActivityIds(workflow));
  }

  @Test
  public void executeActivityWithoutSuppliedIdPreservesGeneratedFallback() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> result = workflow.execute(Invocation.OMITTED_ID);

    assertFalse(result.get(0).isEmpty());
    assertEquals(result, scheduledActivityIds(workflow));
  }

  @Test
  public void completedActivityIdCanBeReused() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> result = workflow.execute(Invocation.REUSED_ID);

    assertEquals(Arrays.asList("reused-activity", "reused-activity"), result);
    assertEquals(result, scheduledActivityIds(workflow));
  }

  @Test
  public void executeActivitySupportsLocalActivityMethodReference() throws Exception {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> activityIds = workflow.execute(Invocation.LOCAL_REFERENCES);

    assertEquals(
        Arrays.asList("typed-local-activity", "untyped-local-activity"), activityIds.subList(0, 2));
    assertFalse(activityIds.get(2).isEmpty());
    WorkflowReplayer.replayWorkflowExecution(history(workflow), TestWorkflowImpl.class);
  }

  @Test
  public void localActivityIdIsPreservedAcrossTimerBackedRetry() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    assertEquals(
        Collections.singletonList("local-retry-activity"),
        workflow.execute(Invocation.LOCAL_RETRY));
  }

  @Test
  public void localActivityRejectsRemoteActivityOptions() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    assertEquals(
        Collections.singletonList("ActivityOptions are not supported for Local Activities"),
        workflow.execute(Invocation.INVALID_LOCAL_OPTIONS));
  }

  @Test
  public void executeActivitySupportsSingleActivityLambda() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> result = workflow.execute(Invocation.LAMBDA);

    assertEquals(Collections.singletonList("lambda-activity"), result);
    assertEquals(result, scheduledActivityIds(workflow));
  }

  @Test
  public void executeActivityPreservesRemoteStubValidationFailure() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    assertTrue(
        workflow
            .execute(Invocation.MISSING_TIMEOUT)
            .get(0)
            .contains("Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified"));
  }

  @Test
  public void invocationActivityOptionsOverrideTypedAndUntypedStubOptions() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    List<String> result =
        workflow.execute(Invocation.OPTIONS_OVERRIDE, "typed-options", "untyped-options");

    assertEquals(Arrays.asList("typed-options", "untyped-options"), result);
    assertEquals(result, scheduledActivityIds(workflow));
    history(workflow).getEvents().stream()
        .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
        .forEach(
            event ->
                assertEquals(
                    ACTIVITY_TIMEOUT.getSeconds(),
                    event
                        .getActivityTaskScheduledEventAttributes()
                        .getStartToCloseTimeout()
                        .getSeconds()));
  }

  @Test
  public void replaySucceedsWhenExplicitActivityIdIsUnchanged() throws Exception {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    assertEquals(Collections.singletonList("replay-activity"), workflow.execute(Invocation.REPLAY));

    WorkflowReplayer.replayWorkflowExecution(history(workflow), TestWorkflowImpl.class);
  }

  @Test
  public void replayFailsWhenExplicitActivityIdChanges() {
    TestWorkflow workflow = newWorkflow(TestWorkflow.class);

    assertEquals(Collections.singletonList("replay-activity"), workflow.execute(Invocation.REPLAY));
    WorkflowExecutionHistory history = history(workflow);
    TestWorkflowImpl.configuredActivityId = "replay-activity-changed";

    assertThrows(
        RuntimeException.class,
        () -> WorkflowReplayer.replayWorkflowExecution(history, TestWorkflowImpl.class));
  }

  private <T> T newWorkflow(Class<T> workflowInterface) {
    return testWorkflowRule.newWorkflowStubTimeoutOptions(workflowInterface);
  }

  private List<String> scheduledActivityIds(Object workflow) {
    return history(workflow).getEvents().stream()
        .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
        .map(event -> event.getActivityTaskScheduledEventAttributes().getActivityId())
        .collect(Collectors.toList());
  }

  private WorkflowExecutionHistory history(Object workflow) {
    WorkflowExecution execution = WorkflowStub.fromTyped(workflow).getExecution();
    return testWorkflowRule
        .getWorkflowClient()
        .fetchHistory(execution.getWorkflowId(), execution.getRunId());
  }

  public enum Invocation {
    TYPED_SYNC,
    CONCURRENT_TYPED,
    CONCURRENT_UNTYPED,
    TYPED_VOID,
    OMITTED_ID,
    REUSED_ID,
    LOCAL_REFERENCES,
    LOCAL_RETRY,
    INVALID_LOCAL_OPTIONS,
    LAMBDA,
    MISSING_TIMEOUT,
    OPTIONS_OVERRIDE,
    REPLAY
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    List<String> execute(Invocation invocation, String... activityIds);
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private static volatile String configuredActivityId = "replay-activity";

    private final InvocationIdActivities activities =
        Workflow.newActivityStub(InvocationIdActivities.class, ACTIVITY_OPTIONS);
    private final ActivityStub untypedActivities =
        Workflow.newUntypedActivityStub(ACTIVITY_OPTIONS);
    private final InvocationIdActivities localActivities =
        Workflow.newLocalActivityStub(InvocationIdActivities.class, LOCAL_ACTIVITY_OPTIONS);
    private final ActivityStub untypedLocalActivities =
        Workflow.newUntypedLocalActivityStub(LOCAL_ACTIVITY_OPTIONS);
    private final InvocationIdActivities activitiesWithoutTimeout =
        Workflow.newActivityStub(
            InvocationIdActivities.class, ActivityOptions.newBuilder().build());
    private final ActivityStub untypedActivitiesWithoutTimeout =
        Workflow.newUntypedActivityStub(ActivityOptions.newBuilder().build());
    private final InvocationIdActivities retryingLocalActivities =
        Workflow.newLocalActivityStub(
            InvocationIdActivities.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(ACTIVITY_TIMEOUT)
                .setLocalRetryThreshold(Duration.ofMillis(1))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMillis(10))
                        .setMaximumAttempts(2)
                        .build())
                .build());

    @Override
    public List<String> execute(Invocation invocation, String... activityIds) {
      switch (invocation) {
        case TYPED_SYNC:
          return Collections.singletonList(
              Workflow.executeActivity(
                  activities::recordActivityId, invocationOptions(activityIds[0])));
        case CONCURRENT_TYPED:
          {
            Promise<String> first =
                Workflow.executeActivityAsync(
                    activities::recordActivityId, invocationOptions(activityIds[0]));
            Promise<String> second =
                Workflow.executeActivityAsync(
                    activities::recordActivityId, invocationOptions(activityIds[1]));
            return Arrays.asList(first.get(), second.get());
          }
        case CONCURRENT_UNTYPED:
          {
            Promise<String> first =
                untypedActivities.executeAsync(
                    "RecordActivityId", String.class, invocationOptions(activityIds[0]));
            Promise<String> second =
                untypedActivities.executeAsync(
                    "RecordActivityId", String.class, invocationOptions(activityIds[1]));
            return Arrays.asList(first.get(), second.get());
          }
        case TYPED_VOID:
          Workflow.executeActivity(activities::recordVoid, invocationOptions(activityIds[0]));
          return Collections.singletonList(activityIds[0]);
        case OMITTED_ID:
          return Collections.singletonList(
              Workflow.executeActivity(
                  activities::recordActivityId, ActivityInvocationOptions.newBuilder().build()));
        case REUSED_ID:
          {
            ActivityInvocationOptions options = invocationOptions("reused-activity");
            String first = Workflow.executeActivity(activities::recordActivityId, options);
            String second = Workflow.executeActivity(activities::recordActivityId, options);
            return Arrays.asList(first, second);
          }
        case LOCAL_REFERENCES:
          {
            String typed =
                Workflow.executeActivity(
                    localActivities::recordActivityId, invocationOptions("typed-local-activity"));
            String untyped =
                untypedLocalActivities.execute(
                    "RecordActivityId", String.class, invocationOptions("untyped-local-activity"));
            String generated =
                Workflow.executeActivity(
                    localActivities::recordActivityId,
                    ActivityInvocationOptions.newBuilder().build());
            return Arrays.asList(typed, untyped, generated);
          }
        case LOCAL_RETRY:
          return Collections.singletonList(
              Workflow.executeActivity(
                  retryingLocalActivities::failOnceAndReturnActivityId,
                  invocationOptions("local-retry-activity")));
        case INVALID_LOCAL_OPTIONS:
          try {
            Workflow.executeActivity(
                localActivities::recordActivityId,
                ActivityInvocationOptions.newBuilder(ACTIVITY_OPTIONS)
                    .setActivityId("invalid-local-activity")
                    .build());
            return Collections.singletonList("unexpected success");
          } catch (IllegalArgumentException e) {
            return Collections.singletonList(e.getMessage());
          }
        case LAMBDA:
          return Collections.singletonList(
              Workflow.executeActivity(
                  () -> activities.recordActivityId(), invocationOptions("lambda-activity")));
        case MISSING_TIMEOUT:
          try {
            Workflow.executeActivity(
                activitiesWithoutTimeout::recordActivityId, invocationOptions("missing-timeout"));
            return Collections.singletonList("unexpected success");
          } catch (IllegalArgumentException e) {
            return Collections.singletonList(e.getMessage());
          }
        case OPTIONS_OVERRIDE:
          {
            String typed =
                Workflow.executeActivity(
                    activitiesWithoutTimeout::recordActivityId,
                    ActivityInvocationOptions.newBuilder(ACTIVITY_OPTIONS)
                        .setActivityId(activityIds[0])
                        .build());
            String untyped =
                untypedActivitiesWithoutTimeout.execute(
                    "RecordActivityId",
                    String.class,
                    ActivityInvocationOptions.newBuilder(ACTIVITY_OPTIONS)
                        .setActivityId(activityIds[1])
                        .build());
            return Arrays.asList(typed, untyped);
          }
        case REPLAY:
          return Collections.singletonList(
              Workflow.executeActivity(
                  activities::recordActivityId, invocationOptions(configuredActivityId)));
      }
      throw new IllegalArgumentException("Unknown invocation: " + invocation);
    }

    private static ActivityInvocationOptions invocationOptions(String activityId) {
      return ActivityInvocationOptions.newBuilder().setActivityId(activityId).build();
    }
  }

  public enum GenericInvocation {
    TYPED_ASYNC,
    UNTYPED_SYNC
  }

  @WorkflowInterface
  public interface GenericWorkflow {
    @WorkflowMethod
    List<UUID> execute(GenericInvocation invocation, List<UUID> values, String activityId);
  }

  public static class GenericWorkflowImpl implements GenericWorkflow {
    private static final Type UUID_LIST_TYPE = new TypeToken<List<UUID>>() {}.getType();

    private final InvocationIdActivities activities =
        Workflow.newActivityStub(InvocationIdActivities.class, ACTIVITY_OPTIONS);
    private final ActivityStub untypedActivities =
        Workflow.newUntypedActivityStub(ACTIVITY_OPTIONS);

    @Override
    public List<UUID> execute(GenericInvocation invocation, List<UUID> values, String activityId) {
      ActivityInvocationOptions options = TestWorkflowImpl.invocationOptions(activityId);
      switch (invocation) {
        case TYPED_ASYNC:
          return Workflow.executeActivityAsync(activities::echoUuidList, options, values).get();
        case UNTYPED_SYNC:
          return untypedActivities.execute(
              "EchoUuidList", List.class, UUID_LIST_TYPE, options, values);
      }
      throw new IllegalArgumentException("Unknown invocation: " + invocation);
    }
  }

  @ActivityInterface
  public interface InvocationIdActivities {
    @ActivityMethod(name = "RecordActivityId")
    String recordActivityId();

    @ActivityMethod(name = "FailOnceAndReturnActivityId")
    String failOnceAndReturnActivityId();

    @ActivityMethod(name = "EchoUuidList")
    List<UUID> echoUuidList(List<UUID> values);

    @ActivityMethod(name = "RecordVoid")
    void recordVoid();
  }

  public static class InvocationIdActivitiesImpl implements InvocationIdActivities {
    @Override
    public String recordActivityId() {
      return Activity.getExecutionContext().getInfo().getActivityId();
    }

    @Override
    public String failOnceAndReturnActivityId() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      if (info.getAttempt() == 1) {
        throw new RuntimeException("intentional first-attempt failure");
      }
      return info.getActivityId();
    }

    @Override
    public List<UUID> echoUuidList(List<UUID> values) {
      return values;
    }

    @Override
    public void recordVoid() {}
  }
}
