package io.temporal.workflow.nexus;

import static org.junit.Assert.*;

import io.temporal.common.interceptors.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import io.temporal.workflow.SignalWithStartWorkflowRequest;
import io.temporal.workflow.SignalWithStartWorkflowResponse;
import org.junit.Rule;
import org.junit.Test;

public class SignalWithStartWorkflowTest {
  @Rule
  public SDKTestWorkflowRule rule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CallerImpl.class)
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
                                    public ExecuteNexusOperationOutput<
                                            SignalWithStartWorkflowResponse>
                                        executeSignalWithStartWorkflow(
                                            ExecuteNexusOperationInput<
                                                    SignalWithStartWorkflowResponse>
                                                input) {
                                      SignalWithStartWorkflowRequest request =
                                          (SignalWithStartWorkflowRequest) input.getArg();
                                      assertEquals("Target", request.getWorkflow());
                                      assertEquals(
                                          Workflow.getInfo().getNamespace(),
                                          request.getNamespace());
                                      assertEquals("notify", request.getSignal());
                                      assertEquals("queue", request.getTaskQueue());
                                      if (request.getId().startsWith("typed")) {
                                        assertEquals(
                                            java.time.Duration.ofSeconds(7),
                                            request.getRetryPolicy().getInitialInterval());
                                        assertEquals(
                                            request.getId().equals("typed-async") ? 9 : 3,
                                            request.getRetryPolicy().getMaximumAttempts());
                                        assertEquals(
                                            request.getId().equals("typed-async")
                                                ? ""
                                                : "0 * * * *",
                                            request.getCronSchedule());
                                      } else {
                                        assertNull(request.getRetryPolicy());
                                        assertNull(request.getCronSchedule());
                                      }
                                      assertEquals(
                                          io.temporal.api.enums.v1.WorkflowIdReusePolicy
                                              .WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE,
                                          request.getIdReusePolicy());
                                      if (request.getId().contains("defaults")) {
                                        assertTrue(request.getArgs().isEmpty());
                                        assertTrue(request.getSignalArgs().isEmpty());
                                      } else {
                                        assertEquals("input", request.getArgs().get(0));
                                        if (request.getId().equals("arity6")) {
                                          assertEquals(
                                              java.util.Arrays.asList(
                                                  "input",
                                                  2,
                                                  3L,
                                                  true,
                                                  java.util.Collections.singletonList("nested"),
                                                  java.util.Collections.singletonMap("key", 4)),
                                              request.getArgs());
                                        }
                                        assertEquals("signal", request.getSignalArgs().get(0));
                                        assertEquals(
                                            "summary",
                                            request.getUserMetadata().getStaticSummary());
                                        assertEquals(
                                            java.time.Duration.ofMinutes(3),
                                            request.getRunTimeout());
                                      }
                                      return new ExecuteNexusOperationOutput<>(
                                          Workflow.newPromise(
                                              SignalWithStartWorkflowResponse.newBuilder()
                                                  .setRunId("run")
                                                  .build()),
                                          Workflow.newPromise(() -> java.util.Optional.empty()));
                                    }
                                  });
                            }
                          };
                        }
                      })
                  .build())
          .build();

  @Test
  public void generatedWorkflowMethodsReturnExternalStubs() {
    assertEquals(
        "sync:run,async:run,defaults:run,typed:run,typed-async:run,typed-defaults:run,typed-null-defaults:run,arity6:run",
        rule.newWorkflowStubTimeoutOptions(Caller.class).run());
  }

  @Test
  public void optionsRequireWorkflowIdAndTaskQueue() {
    assertThrows(
        NullPointerException.class,
        () ->
            Workflow.signalWithStartWorkflow(
                "Target", new Object[0], "notify", new Object[0], null));
    assertThrows(
        NullPointerException.class,
        () -> SignalWithStartWorkflowOptions.newBuilder().setTaskQueue("queue").build());
    assertThrows(
        NullPointerException.class,
        () -> SignalWithStartWorkflowOptions.newBuilder().setId("id").build());
    SignalWithStartWorkflowOptions options =
        SignalWithStartWorkflowOptions.newBuilder().setId("id").setTaskQueue("queue").build();
    assertEquals("id", options.getId());
    assertEquals("queue", options.toBuilder().setId("copy").build().getTaskQueue());
  }

  @WorkflowInterface
  public interface TypedTarget {
    @WorkflowMethod(name = "Target")
    @io.temporal.common.MethodRetry(initialIntervalSeconds = 7, maximumAttempts = 3)
    @io.temporal.common.CronSchedule("0 * * * *")
    int start(String input);

    @SignalMethod(name = "notify")
    @io.temporal.common.MethodRetry(maximumAttempts = 99)
    @io.temporal.common.CronSchedule("signal-annotation-must-not-be-used")
    void signalValue(String value);
  }

  @WorkflowInterface
  public interface EmptyTarget {
    @WorkflowMethod(name = "Target")
    @io.temporal.common.MethodRetry(initialIntervalSeconds = 7, maximumAttempts = 3)
    @io.temporal.common.CronSchedule("0 * * * *")
    void start();

    @SignalMethod(name = "notify")
    void signalValue();
  }

  @WorkflowInterface
  public interface SixArgumentTarget {
    @WorkflowMethod(name = "Target")
    java.util.List<String> start(
        String first,
        int second,
        long third,
        boolean fourth,
        java.util.List<String> fifth,
        java.util.Map<String, Integer> sixth);

    @SignalMethod(name = "notify")
    void signalValue(String value);
  }

  @WorkflowInterface
  public interface Caller {
    @WorkflowMethod
    String run();
  }

  public static class CallerImpl implements Caller {
    @Override
    public String run() {
      SignalWithStartWorkflowOptions options =
          SignalWithStartWorkflowOptions.newBuilder()
              .setId("sync")
              .setTaskQueue("queue")
              .setStaticSummary("summary")
              .setRunTimeout(java.time.Duration.ofMinutes(3))
              .build();
      ExternalWorkflowStub sync =
          Workflow.signalWithStartWorkflow(
              "Target", new Object[] {"input"}, "notify", new Object[] {"signal"}, options);
      ExternalWorkflowStub async =
          Async.function(
                  () ->
                      Workflow.signalWithStartWorkflow(
                          "Target",
                          new Object[] {"input"},
                          "notify",
                          new Object[] {"signal"},
                          options.toBuilder().setId("async").build()))
              .get();
      ExternalWorkflowStub defaults =
          Workflow.signalWithStartWorkflow(
              "Target",
              new Object[0],
              "notify",
              new Object[0],
              SignalWithStartWorkflowOptions.newBuilder()
                  .setId("defaults")
                  .setTaskQueue("queue")
                  .build());
      SignalWithStartWorkflowOptions typedOptions = options;
      TypedTarget target = Workflow.newExternalWorkflowStub(TypedTarget.class, "typed");
      ExternalWorkflowStub typed =
          Workflow.signalWithStartWorkflow(
              target::start,
              "input",
              target::signalValue,
              "signal",
              typedOptions.toBuilder().setId("typed").build());
      ExternalWorkflowStub typedAsync =
          Async.function(
                  () ->
                      Workflow.signalWithStartWorkflow(
                          target::start,
                          "input",
                          target::signalValue,
                          "signal",
                          typedOptions.toBuilder()
                              .setId("typed-async")
                              .setRetryPolicy(
                                  io.temporal.common.RetryOptions.newBuilder()
                                      .setMaximumAttempts(9)
                                      .build())
                              .setCronSchedule("")
                              .build()))
              .get();
      EmptyTarget empty = Workflow.newExternalWorkflowStub(EmptyTarget.class, "typed-defaults");
      ExternalWorkflowStub typedDefaults =
          Workflow.signalWithStartWorkflow(
              empty::start,
              empty::signalValue,
              SignalWithStartWorkflowOptions.newBuilder()
                  .setId("typed-defaults")
                  .setTaskQueue("queue")
                  .build());
      ExternalWorkflowStub typedNull =
          Workflow.signalWithStartWorkflow(
              empty::start,
              empty::signalValue,
              SignalWithStartWorkflowOptions.newBuilder()
                  .setId("typed-null-defaults")
                  .setTaskQueue("queue")
                  .build());
      assertThrows(
          NullPointerException.class,
          () -> Workflow.signalWithStartWorkflow(empty::start, empty::signalValue, null));
      SixArgumentTarget six = Workflow.newExternalWorkflowStub(SixArgumentTarget.class, "arity6");
      ExternalWorkflowStub arity6 =
          Workflow.signalWithStartWorkflow(
              six::start,
              "input",
              2,
              3L,
              true,
              java.util.Collections.singletonList("nested"),
              java.util.Collections.singletonMap("key", 4),
              six::signalValue,
              "signal",
              typedOptions.toBuilder().setId("arity6").build());
      return sync.getExecution().getWorkflowId()
          + ":"
          + sync.getExecution().getRunId()
          + ","
          + async.getExecution().getWorkflowId()
          + ":"
          + async.getExecution().getRunId()
          + ","
          + defaults.getExecution().getWorkflowId()
          + ":"
          + defaults.getExecution().getRunId()
          + ","
          + typed.getExecution().getWorkflowId()
          + ":"
          + typed.getExecution().getRunId()
          + ","
          + typedAsync.getExecution().getWorkflowId()
          + ":"
          + typedAsync.getExecution().getRunId()
          + ","
          + typedDefaults.getExecution().getWorkflowId()
          + ":"
          + typedDefaults.getExecution().getRunId()
          + ","
          + typedNull.getExecution().getWorkflowId()
          + ":"
          + typedNull.getExecution().getRunId()
          + ","
          + arity6.getExecution().getWorkflowId()
          + ":"
          + arity6.getExecution().getRunId();
    }
  }
}
