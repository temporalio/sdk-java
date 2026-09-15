package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowUnsafeReadOnlyTest {
  private static final Map<String, Boolean> calls = new ConcurrentHashMap<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ReadOnlyWorkflowImpl.class)
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new ReadOnlyRecordingInterceptor())
                  .build())
          .build();

  @Before
  public void setUp() {
    calls.clear();
  }

  @Test
  public void isReadOnly() {
    ReadOnlyWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ReadOnlyWorkflow.class);
    WorkflowClient.start(workflow::run);

    workflow.query();
    workflow.update();
    workflow.finish();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    Map<String, Boolean> expected = new ConcurrentHashMap<>();
    expected.put("ExecuteWorkflow", false);
    expected.put("workflowTask", false);
    expected.put("ExecuteUpdate", false);
    expected.put("updateHandler", false);
    expected.put("HandleSignal", false);
    expected.put("sideEffect", true);
    expected.put("await", true);
    expected.put("HandleQuery", true);
    expected.put("query", true);
    expected.put("ValidateUpdate", true);
    expected.put("validator", true);
    assertEquals(expected, calls);
  }

  private static void record(String name) {
    calls.put(name, WorkflowUnsafe.isReadOnly());
  }

  @WorkflowInterface
  public interface ReadOnlyWorkflow {
    @WorkflowMethod
    void run();

    @QueryMethod
    boolean query();

    @UpdateMethod
    void update();

    @UpdateValidatorMethod(updateName = "update")
    void validateUpdate();

    @SignalMethod
    void finish();
  }

  public static class ReadOnlyWorkflowImpl implements ReadOnlyWorkflow {
    private boolean finished;

    @Override
    public void run() {
      record("workflowTask");
      Workflow.sideEffect(
          Void.class,
          () -> {
            record("sideEffect");
            return null;
          });
      Workflow.await(
          () -> {
            record("await");
            return finished;
          });
    }

    @Override
    public boolean query() {
      record("query");
      return true;
    }

    @Override
    public void update() {
      record("updateHandler");
    }

    @Override
    public void validateUpdate() {
      record("validator");
    }

    @Override
    public void finish() {
      finished = true;
    }
  }

  private static class ReadOnlyRecordingInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public WorkflowOutput execute(WorkflowInput input) {
          record("ExecuteWorkflow");
          return super.execute(input);
        }

        @Override
        public void handleSignal(SignalInput input) {
          record("HandleSignal");
          super.handleSignal(input);
        }

        @Override
        public QueryOutput handleQuery(QueryInput input) {
          record("HandleQuery");
          return super.handleQuery(input);
        }

        @Override
        public void validateUpdate(UpdateInput input) {
          record("ValidateUpdate");
          super.validateUpdate(input);
        }

        @Override
        public UpdateOutput executeUpdate(UpdateInput input) {
          record("ExecuteUpdate");
          return super.executeUpdate(input);
        }
      };
    }
  }
}
