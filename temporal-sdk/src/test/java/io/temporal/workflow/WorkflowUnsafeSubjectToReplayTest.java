package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowUnsafeSubjectToReplayTest {
  private static final Map<String, Boolean> calls = new ConcurrentHashMap<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(SubjectToReplayWorkflowImpl.class)
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new SubjectToReplayRecordingInterceptor())
                  .build())
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setPreferredVersionProvider(
                      input -> {
                        record("versionProvider");
                        return null;
                      })
                  .build())
          .build();

  @Before
  public void setUp() {
    calls.clear();
  }

  /**
   * Subjection to replay is a property of the calling context rather than of the Workflow's current
   * state, so running once live covers the whole contract.
   */
  @Test
  public void isSubjectToReplay() {
    SubjectToReplayWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SubjectToReplayWorkflow.class);
    WorkflowClient.start(workflow::run);

    workflow.query();
    workflow.update();
    workflow.finish();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    Map<String, Boolean> expected = new ConcurrentHashMap<>();
    // The durable Workflow path re-executes on every replay.
    expected.put("ExecuteWorkflow", true);
    expected.put("workflowTask", true);
    expected.put("ExecuteUpdate", true);
    expected.put("updateHandler", true);
    expected.put("HandleSignal", true);
    // An Await condition is read-only yet still re-evaluated on replay. This is the one context
    // where subjection to replay and read-only disagree, and the reason the two are separate.
    expected.put("await", true);
    // Live callbacks run once against current state and are never re-executed from history.
    expected.put("sideEffect", false);
    expected.put("mutableSideEffect", false);
    expected.put("versionProvider", false);
    expected.put("HandleQuery", false);
    expected.put("query", false);
    expected.put("ValidateUpdate", false);
    expected.put("validator", false);
    assertEquals(expected, calls);
  }

  private static void record(String name) {
    calls.put(name, WorkflowUnsafe.isSubjectToReplay());
  }

  @WorkflowInterface
  public interface SubjectToReplayWorkflow {
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

  public static class SubjectToReplayWorkflowImpl implements SubjectToReplayWorkflow {
    private boolean finished;

    @Override
    public void run() {
      record("workflowTask");
      Workflow.getVersion("change", Workflow.DEFAULT_VERSION, 1);
      Workflow.sideEffect(
          Void.class,
          () -> {
            record("sideEffect");
            return null;
          });
      Workflow.mutableSideEffect(
          "id",
          Integer.class,
          Integer::equals,
          () -> {
            record("mutableSideEffect");
            return 1;
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

  private static class SubjectToReplayRecordingInterceptor extends WorkerInterceptorBase {
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
