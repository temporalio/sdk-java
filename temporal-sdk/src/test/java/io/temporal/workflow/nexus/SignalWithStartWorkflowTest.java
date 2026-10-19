package io.temporal.workflow.nexus;

import static org.junit.Assert.*;

import io.temporal.common.interceptors.*;
import io.temporal.nexus.system.workflowservice.SignalWithStartWorkflowRequest;
import io.temporal.nexus.system.workflowservice.SignalWithStartWorkflowResponse;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
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
                                      assertEquals("input", request.getArgs().get(0));
                                      assertEquals("signal", request.getSignalArgs().get(0));
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
  public void typedUntypedAndAsyncCallsReturnExternalStubs() {
    assertEquals(
        "typed:run,untyped:run,async:run", rule.newWorkflowStubTimeoutOptions(Caller.class).run());
  }

  @WorkflowInterface
  public interface Target {
    @WorkflowMethod(name = "Target")
    String run(String value);

    @SignalMethod(name = "notify")
    void notifySignal(String value);

    @QueryMethod
    String query();
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
              .setWorkflowId("typed")
              .setTaskQueue("queue")
              .build();
      Target target = Workflow.newSignalWithStartWorkflowStub(Target.class, options);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Workflow.signalWithStart(
                  target,
                  () -> {
                    target.run("input");
                    target.run("duplicate");
                  },
                  () -> target.notifySignal("signal")));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              Workflow.signalWithStart(
                  target, () -> target.query(), () -> target.notifySignal("signal")));
      Target other = Workflow.newSignalWithStartWorkflowStub(Target.class, options);
      assertThrows(
          IllegalStateException.class,
          () ->
              Workflow.signalWithStart(
                  target, () -> other.run("input"), () -> target.notifySignal("signal")));
      Target external =
          Workflow.signalWithStart(
              target, () -> target.run("input"), () -> target.notifySignal("signal"));
      String typed =
          Workflow.getWorkflowExecution(external).get().getWorkflowId()
              + ":"
              + Workflow.getWorkflowExecution(external).get().getRunId();
      ExternalWorkflowStub untyped =
          Workflow.newUntypedSignalWithStartWorkflowStub(
                  "Target", options.toBuilder().setWorkflowId("untyped").build())
              .signalWithStart("notify", new Object[] {"signal"}, "input");
      Target asyncTarget =
          Workflow.newSignalWithStartWorkflowStub(
              Target.class, options.toBuilder().setWorkflowId("async").build());
      Target async =
          Async.function(
                  () ->
                      Workflow.signalWithStart(
                          asyncTarget,
                          () -> asyncTarget.run("input"),
                          () -> asyncTarget.notifySignal("signal")))
              .get();
      return typed
          + ","
          + untyped.getExecution().getWorkflowId()
          + ":"
          + untyped.getExecution().getRunId()
          + ","
          + Workflow.getWorkflowExecution(async).get().getWorkflowId()
          + ":"
          + Workflow.getWorkflowExecution(async).get().getRunId();
    }
  }
}
