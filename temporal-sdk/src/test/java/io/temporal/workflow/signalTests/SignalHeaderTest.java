package io.temporal.workflow.signalTests;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalOutput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor.SignalInput;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that headers attached to a signal by a client interceptor reach the inbound workflow
 * interceptor when running against the (time-skipping) test server. Regression test for the test
 * server dropping the header while building the WorkflowExecutionSignaled event.
 */
public class SignalHeaderTest {

  private static final String HEADER_KEY = "signal-header-key";
  private static final String HEADER_VALUE = "signal-header-value";
  private static final AtomicReference<String> RECEIVED_HEADER = new AtomicReference<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestSignalWorkflowImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new SignalHeaderClientInterceptor())
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new SignalHeaderWorkerInterceptor())
                  .validateAndBuildWithDefaults())
          .build();

  @Test
  public void headerIsPropagatedToInboundSignalHandler() {
    TestSignalWorkflow workflow = testWorkflowRule.newWorkflowStub(TestSignalWorkflow.class);
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    stub.start();
    workflow.unblock();
    stub.getResult(Void.class);
    assertEquals(HEADER_VALUE, RECEIVED_HEADER.get());
  }

  @WorkflowInterface
  public interface TestSignalWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void unblock();
  }

  public static class TestSignalWorkflowImpl implements TestSignalWorkflow {
    private boolean unblocked = false;

    @Override
    public void execute() {
      Workflow.await(() -> unblocked);
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }

  /** Adds a header to the outbound signal. */
  private static class SignalHeaderClientInterceptor extends WorkflowClientInterceptorBase {
    @Override
    public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
        WorkflowClientCallsInterceptor next) {
      return new WorkflowClientCallsInterceptorBase(next) {
        @Override
        public WorkflowSignalOutput signal(WorkflowSignalInput input) {
          Map<String, Payload> values = new HashMap<>(input.getHeader().getValues());
          values.put(
              HEADER_KEY,
              Payload.newBuilder().setData(ByteString.copyFromUtf8(HEADER_VALUE)).build());
          return super.signal(
              new WorkflowSignalInput(
                  input.getWorkflowExecution(),
                  input.getSignalName(),
                  new Header(values),
                  input.getArguments()));
        }
      };
    }
  }

  /** Captures the header seen by the inbound signal handler. */
  private static class SignalHeaderWorkerInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void handleSignal(SignalInput input) {
          Payload payload = input.getHeader().getValues().get(HEADER_KEY);
          if (payload != null) {
            RECEIVED_HEADER.set(payload.getData().toStringUtf8());
          }
          super.handleSignal(input);
        }
      };
    }
  }
}
