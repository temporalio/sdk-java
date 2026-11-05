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
  private final java.util.concurrent.atomic.AtomicInteger scheduledOperations =
      new java.util.concurrent.atomic.AtomicInteger();

  @Rule
  public SDKTestWorkflowRule rule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CallerImpl.class, IdentityCallerImpl.class)
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
                                      scheduledOperations.incrementAndGet();
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
                                            request.getId().equals("typed-overrides") ? 9 : 3,
                                            request.getRetryPolicy().getMaximumAttempts());
                                        assertEquals(
                                            request.getId().equals("typed-overrides")
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
        "sync:run,defaults:run,typed:run,typed-overrides:run,typed-defaults:run,arity6:run",
        rule.newWorkflowStubTimeoutOptions(Caller.class).run());
  }

  @Test
  public void optionsDeferWorkflowIdValidationUntilInvocation() {
    assertThrows(
        NullPointerException.class,
        () ->
            Workflow.signalWithStartWorkflow(
                "Target", new Object[0], "notify", new Object[0], null));
    SignalWithStartWorkflowOptions withoutId =
        SignalWithStartWorkflowOptions.newBuilder().setTaskQueue("queue").build();
    assertNull(withoutId.getId());
    assertEquals(
        "id",
        assertThrows(
                NullPointerException.class,
                () ->
                    Workflow.signalWithStartWorkflow(
                        "Target", new Object[0], "notify", new Object[0], withoutId))
            .getMessage());
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

  @Test
  public void workflowReferenceDeterminesIdentityAndSignalBindingIsIgnored() {
    assertEquals(
        java.util.Arrays.asList("typed-identity-a", "typed-identity-b"),
        rule.newWorkflowStubTimeoutOptions(IdentityCaller.class).run());
    assertEquals(2, scheduledOperations.get());
  }

  @WorkflowInterface
  public interface IdentityCaller {
    @WorkflowMethod
    java.util.List<String> run();
  }

  public static class IdentityCallerImpl implements IdentityCaller {
    @Override
    public java.util.List<String> run() {
      SignalWithStartWorkflowOptions options =
          SignalWithStartWorkflowOptions.newBuilder()
              .setTaskQueue("queue")
              .setStaticSummary("summary")
              .setRunTimeout(java.time.Duration.ofMinutes(3))
              .build();
      TypedTarget first = Workflow.newExternalWorkflowStub(TypedTarget.class, "typed-identity-a");
      TypedTarget second = Workflow.newExternalWorkflowStub(TypedTarget.class, "typed-identity-b");
      TypedTarget signal =
          Workflow.newExternalWorkflowStub(
              TypedTarget.class,
              io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                  .setWorkflowId("ignored-signal-id")
                  .setRunId("ignored-signal-run")
                  .build());
      ExternalWorkflowStub a =
          Workflow.signalWithStartWorkflow(
              first::start, "input", signal::signalValue, "signal", options);
      ExternalWorkflowStub b =
          Workflow.signalWithStartWorkflow(
              second::start, "input", signal::signalValue, "signal", options);
      assertNull(options.getId());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Workflow.signalWithStartWorkflow(
                  first::start,
                  "input",
                  signal::signalValue,
                  "signal",
                  options.toBuilder().setId("different-id").build()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Workflow.signalWithStartWorkflow(
                  first::start,
                  "input",
                  signal::signalValue,
                  "signal",
                  options.toBuilder().setId("").build()));
      TypedTarget pinned =
          Workflow.newExternalWorkflowStub(
              TypedTarget.class,
              io.temporal.api.common.v1.WorkflowExecution.newBuilder()
                  .setWorkflowId("typed-identity-a")
                  .setRunId("pinned-run")
                  .build());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Workflow.signalWithStartWorkflow(
                  pinned::start, "input", signal::signalValue, "signal", options));
      TypedTarget missingId = Workflow.newExternalWorkflowStub(TypedTarget.class, "");
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Workflow.signalWithStartWorkflow(
                  missingId::start, "input", signal::signalValue, "signal", options));
      return java.util.Arrays.asList(
          a.getExecution().getWorkflowId(), b.getExecution().getWorkflowId());
    }
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
      ExternalWorkflowStub typedOverrides =
          Workflow.signalWithStartWorkflow(
              Workflow.newExternalWorkflowStub(TypedTarget.class, "typed-overrides")::start,
              "input",
              target::signalValue,
              "signal",
              typedOptions.toBuilder()
                  .setId("typed-overrides")
                  .setRetryPolicy(
                      io.temporal.common.RetryOptions.newBuilder().setMaximumAttempts(9).build())
                  .setCronSchedule("")
                  .build());
      EmptyTarget empty = Workflow.newExternalWorkflowStub(EmptyTarget.class, "typed-defaults");
      ExternalWorkflowStub typedDefaults =
          Workflow.signalWithStartWorkflow(
              empty::start,
              empty::signalValue,
              SignalWithStartWorkflowOptions.newBuilder().setTaskQueue("queue").build());
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
          + defaults.getExecution().getWorkflowId()
          + ":"
          + defaults.getExecution().getRunId()
          + ","
          + typed.getExecution().getWorkflowId()
          + ":"
          + typed.getExecution().getRunId()
          + ","
          + typedOverrides.getExecution().getWorkflowId()
          + ":"
          + typedOverrides.getExecution().getRunId()
          + ","
          + typedDefaults.getExecution().getWorkflowId()
          + ":"
          + typedDefaults.getExecution().getRunId()
          + ","
          + arity6.getExecution().getWorkflowId()
          + ":"
          + arity6.getExecution().getRunId();
    }
  }
}
