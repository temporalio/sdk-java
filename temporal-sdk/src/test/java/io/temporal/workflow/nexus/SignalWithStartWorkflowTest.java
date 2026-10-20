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
                                      assertEquals(
                                          io.temporal.api.enums.v1.WorkflowIdReusePolicy
                                              .WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE,
                                          request.getIdReusePolicy());
                                      if (request.getId().equals("defaults")) {
                                        assertNull(request.getArgs());
                                        assertNull(request.getSignalArgs());
                                      } else {
                                        assertEquals("input", request.getArgs().get(0));
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
        "sync:run,async:run,defaults:run", rule.newWorkflowStubTimeoutOptions(Caller.class).run());
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
              .setArgs("input")
              .setSignalArgs("signal")
              .setStaticSummary("summary")
              .setRunTimeout(java.time.Duration.ofMinutes(3))
              .build();
      ExternalWorkflowStub sync =
          Workflow.signalWithStartWorkflow("Target", "sync", "queue", "notify", options);
      ExternalWorkflowStub async =
          Async.function(
                  Workflow::signalWithStartWorkflow,
                  "Target",
                  "async",
                  "queue",
                  "notify",
                  options.toBuilder().build())
              .get();
      ExternalWorkflowStub defaults =
          Workflow.signalWithStartWorkflow("Target", "defaults", "queue", "notify");
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
          + defaults.getExecution().getRunId();
    }
  }
}
