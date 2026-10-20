package io.temporal.workflow.nexus;

import static org.junit.Assert.*;

import io.temporal.common.converter.DefaultDataConverter;
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
                                          "input",
                                          DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
                                              0,
                                              java.util.Optional.of(request.getArgs()),
                                              String.class,
                                              String.class));
                                      assertEquals(
                                          "signal",
                                          DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
                                              0,
                                              java.util.Optional.of(request.getSignalArgs()),
                                              String.class,
                                              String.class));
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
    assertEquals("sync:run,async:run", rule.newWorkflowStubTimeoutOptions(Caller.class).run());
  }

  @WorkflowInterface
  public interface Caller {
    @WorkflowMethod
    String run();
  }

  public static class CallerImpl implements Caller {
    @Override
    public String run() {
      SignalWithStartWorkflowRequest request =
          SignalWithStartWorkflowRequest.newBuilder()
              .setWorkflow("Target")
              .setSignal("notify")
              .setId("sync")
              .setTaskQueue("queue")
              .setArgs(DefaultDataConverter.STANDARD_INSTANCE.toPayloads("input").get())
              .setSignalArgs(DefaultDataConverter.STANDARD_INSTANCE.toPayloads("signal").get())
              .build();
      ExternalWorkflowStub sync = Workflow.signalWithStartWorkflow(request);
      ExternalWorkflowStub async =
          Async.function(
                  Workflow::signalWithStartWorkflow, request.toBuilder().setId("async").build())
              .get();
      return sync.getExecution().getWorkflowId()
          + ":"
          + sync.getExecution().getRunId()
          + ","
          + async.getExecution().getWorkflowId()
          + ":"
          + async.getExecution().getRunId();
    }
  }
}
