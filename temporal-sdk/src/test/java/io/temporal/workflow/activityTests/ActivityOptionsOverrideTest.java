package io.temporal.workflow.activityTests;

import static org.junit.Assert.*;

import io.temporal.activity.*;
import io.temporal.client.WorkflowStub;
import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.common.interceptors.*;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Rule;
import org.junit.Test;

public class ActivityOptionsOverrideTest {
  private final Map<String, ActivityOptions> observedOptions = new ConcurrentHashMap<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(OverrideWorkflow.class)
          .setActivityImplementations(new ActivitiesImpl())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(
                      new WorkerInterceptorBase() {
                        @Override
                        public WorkflowInboundCallsInterceptor interceptWorkflow(
                            WorkflowInboundCallsInterceptor next) {
                          return new WorkflowInboundCallsInterceptorBase(next) {
                            @Override
                            public void init(WorkflowOutboundCallsInterceptor outbound) {
                              super.init(
                                  new WorkflowOutboundCallsInterceptorBase(outbound) {
                                    @Override
                                    public <R> ActivityOutput<R> executeActivity(
                                        ActivityInput<R> input) {
                                      observedOptions.put(
                                          input.getArgs()[0].toString(), input.getOptions());
                                      return super.executeActivity(input);
                                    }
                                  });
                            }
                          };
                        }
                      })
                  .build())
          .build();

  @Test
  public void overridesMethodOptionsWithoutChangingOriginalAndReplays() throws Exception {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    assertEquals("done", workflow.execute("sync"));
    assertTrue(observedOptions.get("original").isEagerExecutionDisabled());
    assertFalse(observedOptions.get("override").isEagerExecutionDisabled());
    assertEquals(2, observedOptions.get("override").getRetryOptions().getMaximumAttempts());
    assertEquals(3, observedOptions.get("retry").getRetryOptions().getMaximumAttempts());
    assertNull(observedOptions.get("cleared-retry").getRetryOptions());
    assertEquals(2, observedOptions.get("after").getRetryOptions().getMaximumAttempts());
    WorkflowReplayer.replayWorkflowExecution(
        testWorkflowRule
            .getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId()),
        OverrideWorkflow.class);
  }

  @Test
  public void asyncCallsAreIndependent() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    assertEquals("done", workflow.execute("async"));
  }

  @Test
  public void invalidOptionsAndCallbackFailuresLeaveOriginalUsable() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    assertEquals("done", workflow.execute("invalid"));
  }

  @Test
  public void retainsWorkflowDefaults() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    assertEquals("done", workflow.execute("defaults"));
    assertEquals(testWorkflowRule.getTaskQueue(), observedOptions.get("defaults").getTaskQueue());
  }

  @Test
  public void untypedCallsUseTheSameOverrides() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    assertEquals("done", workflow.execute("untyped"));
  }

  public interface BaseActivities {
    @MethodRetry(maximumAttempts = 2)
    List<String> inspect(String label);
  }

  @ActivityInterface(namePrefix = "Override_")
  public interface Activities extends BaseActivities {
    @MethodRetry(maximumAttempts = 1)
    void verify(long expectedTimeoutSeconds);
  }

  public static class ActivitiesImpl implements Activities {
    @Override
    public List<String> inspect(String label) {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      if (label.equals("override")) {
        assertEquals("override-id", info.getActivityId());
      }
      return Arrays.asList(
          label,
          Long.toString(info.getStartToCloseTimeout().getSeconds()),
          Long.toString(info.getScheduleToCloseTimeout().getSeconds()),
          Long.toString(info.getHeartbeatTimeout().getSeconds()));
    }

    @Override
    public void verify(long expectedTimeoutSeconds) {
      assertEquals(
          expectedTimeoutSeconds,
          Activity.getExecutionContext().getInfo().getStartToCloseTimeout().getSeconds());
    }
  }

  public static class OverrideWorkflow implements TestWorkflow1 {
    @Override
    public String execute(String scenario) {
      Activities original =
          Workflow.newActivityStub(
              Activities.class,
              ActivityOptions.newBuilder()
                  .setStartToCloseTimeout(Duration.ofSeconds(10))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(100))
                  .setHeartbeatTimeout(Duration.ofSeconds(5))
                  .setDisableEagerExecution(true)
                  .build(),
              Collections.singletonMap(
                  "Override_Inspect",
                  ActivityOptions.newBuilder()
                      .setStartToCloseTimeout(Duration.ofSeconds(20))
                      .build()));
      ActivityInvocationOptions overrides =
          ActivityInvocationOptions.newBuilder()
              .setActivityOptionsOverride(
                  b ->
                      b.setStartToCloseTimeout(Duration.ofSeconds(30))
                          .setDisableEagerExecution(false))
              .build();

      switch (scenario) {
        case "sync":
          assertEquals(Arrays.asList("original", "20", "100", "5"), original.inspect("original"));
          assertEquals(
              Arrays.asList("override", "30", "100", "5"),
              Workflow.executeActivity(
                  original::inspect,
                  overrides.toBuilder().setActivityId("override-id").build(),
                  "override"));
          Workflow.executeActivity(original::verify, overrides, 30L);
          original.verify(10);
          ActivityInvocationOptions retryOverride =
              ActivityInvocationOptions.newBuilder()
                  .setActivityOptionsOverride(
                      b -> {
                        assertEquals(2, b.build().getRetryOptions().getMaximumAttempts());
                        b.setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build());
                      })
                  .build();
          assertEquals(
              Arrays.asList("retry", "20", "100", "5"),
              Workflow.executeActivity(original::inspect, retryOverride, "retry"));
          ActivityInvocationOptions clearedRetry =
              ActivityInvocationOptions.newBuilder()
                  .setActivityOptionsOverride(b -> b.setRetryOptions(null))
                  .build();
          assertEquals(
              Arrays.asList("cleared-retry", "20", "100", "5"),
              Workflow.executeActivity(original::inspect, clearedRetry, "cleared-retry"));
          assertEquals(Arrays.asList("after", "20", "100", "5"), original.inspect("after"));
          break;
        case "async":
          ActivityInvocationOptions longer =
              ActivityInvocationOptions.newBuilder()
                  .setActivityOptionsOverride(
                      b ->
                          b.setStartToCloseTimeout(Duration.ofSeconds(40))
                              .setHeartbeatTimeout(Duration.ofSeconds(7)))
                  .build();
          ActivityInvocationOptions cleared =
              ActivityInvocationOptions.newBuilder()
                  .setActivityOptionsOverride(b -> b.setHeartbeatTimeout(null))
                  .build();
          Promise<List<String>> first =
              Workflow.executeActivityAsync(original::inspect, longer, "longer");
          Promise<List<String>> second = Async.function(original::inspect, "original");
          Promise<List<String>> third =
              Workflow.executeActivityAsync(original::inspect, cleared, "cleared");
          Workflow.executeActivityAsync(original::verify, overrides, 30L).get();
          assertEquals(Arrays.asList("longer", "40", "100", "7"), first.get());
          assertEquals(Arrays.asList("original", "20", "100", "5"), second.get());
          assertEquals(Arrays.asList("cleared", "20", "100", "0"), third.get());
          assertEquals(Arrays.asList("after", "20", "100", "5"), original.inspect("after"));
          break;
        case "invalid":
          ActivityInvocationOptions invalid =
              ActivityInvocationOptions.newBuilder()
                  .setActivityOptionsOverride(
                      b -> b.setStartToCloseTimeout(null).setScheduleToCloseTimeout(null))
                  .build();
          assertThrows(
              IllegalArgumentException.class,
              () -> Workflow.executeActivity(original::inspect, invalid, "invalid"));
          ActivityInvocationOptions throwing =
              ActivityInvocationOptions.newBuilder()
                  .setActivityOptionsOverride(
                      b -> {
                        throw new IllegalStateException("override failed");
                      })
                  .build();
          assertThrows(
              IllegalStateException.class,
              () -> Workflow.executeActivity(original::inspect, throwing, "throwing"));
          IllegalStateException asyncFailure =
              assertThrows(
                  IllegalStateException.class,
                  () ->
                      Workflow.executeActivityAsync(original::inspect, throwing, "throwing-async"));
          assertEquals("override failed", asyncFailure.getMessage());
          assertEquals(
              Arrays.asList("after-async-failure", "30", "100", "5"),
              Workflow.executeActivityAsync(original::inspect, overrides, "after-async-failure")
                  .get());
          Activities local =
              Workflow.newLocalActivityStub(
                  Activities.class,
                  LocalActivityOptions.newBuilder()
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .build());
          assertThrows(
              IllegalArgumentException.class,
              () -> Workflow.executeActivity(local::inspect, overrides, "local"));
          ActivityStub untypedLocal =
              Workflow.newUntypedLocalActivityStub(
                  LocalActivityOptions.newBuilder()
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .build());
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  untypedLocal.executeAsync(
                      "Override_Inspect", List.class, List.class, overrides, "local"));
          assertEquals(Arrays.asList("after", "20", "100", "5"), original.inspect("after"));
          break;
        case "defaults":
          Workflow.setDefaultActivityOptions(
              ActivityOptions.newBuilder()
                  .setTaskQueue(Workflow.getInfo().getTaskQueue())
                  .setStartToCloseTimeout(Duration.ofSeconds(10))
                  .setScheduleToCloseTimeout(Duration.ofSeconds(100))
                  .setHeartbeatTimeout(Duration.ofSeconds(5))
                  .build());
          Activities defaults = Workflow.newActivityStub(Activities.class);
          assertEquals(
              Arrays.asList("defaults", "30", "100", "5"),
              Workflow.executeActivity(defaults::inspect, overrides, "defaults"));
          assertEquals(Arrays.asList("unchanged", "10", "100", "5"), defaults.inspect("unchanged"));
          Activities unconfigured =
              Workflow.newActivityStub(Activities.class, ActivityOptions.newBuilder().build());
          ActivityInvocationOptions timeouts =
              ActivityInvocationOptions.newBuilder()
                  .setActivityOptionsOverride(
                      b ->
                          b.setStartToCloseTimeout(Duration.ofSeconds(30))
                              .setScheduleToCloseTimeout(Duration.ofSeconds(100)))
                  .build();
          assertEquals(
              Arrays.asList("timeouts", "30", "100", "0"),
              Workflow.executeActivity(unconfigured::inspect, timeouts, "timeouts"));
          break;
        case "untyped":
          ActivityStub untyped =
              Workflow.newUntypedActivityStub(
                  ActivityOptions.newBuilder()
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .setScheduleToCloseTimeout(Duration.ofSeconds(100))
                      .setHeartbeatTimeout(Duration.ofSeconds(5))
                      .build());
          assertEquals(
              Arrays.asList("untyped", "30", "100", "5"),
              untyped.execute("Override_Inspect", List.class, List.class, overrides, "untyped"));
          assertEquals(
              Arrays.asList("async", "30", "100", "5"),
              untyped
                  .executeAsync("Override_Inspect", List.class, List.class, overrides, "async")
                  .get());
          assertEquals(
              Arrays.asList("after", "10", "100", "5"),
              untyped.execute("Override_Inspect", List.class, "after"));
          break;
        default:
          throw new IllegalArgumentException(scenario);
      }
      return "done";
    }
  }
}
