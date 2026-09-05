package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.internal.sync.ReadOnlyException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.VersionPreference;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
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
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setPreferredVersionProvider(
                      input -> {
                        record("preferredVersionProvider");
                        return VersionPreference.of(Workflow.DEFAULT_VERSION);
                      })
                  .build())
          .build();

  @Before
  public void setUp() {
    calls.clear();
  }

  @Test
  public void falseOutsideWorkflow() {
    assertFalse(WorkflowUnsafe.isReadOnly());
  }

  @Test
  public void reportsEveryReadOnlyWorkflowContext() {
    ReadOnlyWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ReadOnlyWorkflow.class);
    WorkflowClient.start(workflow::run);

    assertTrue(workflow.query());
    assertEquals("updated", workflow.update());
    workflow.finish();
    assertEquals("done", WorkflowStub.fromTyped(workflow).getResult(String.class));

    assertEquals(expectedCalls(), calls);
  }

  private static Map<String, Boolean> expectedCalls() {
    Map<String, Boolean> expected = new ConcurrentHashMap<>();
    expected.put("execute", false);
    expected.put("workflow", false);
    expected.put("sideEffect", true);
    expected.put("mutableSideEffect", true);
    expected.put("await", true);
    expected.put("preferredVersionProvider", true);
    expected.put("handleQuery", true);
    expected.put("query", true);
    expected.put("validateUpdate", true);
    expected.put("validator", true);
    expected.put("executeUpdate", false);
    expected.put("update", false);
    expected.put("handleSignal", false);
    expected.put("signal", false);
    return expected;
  }

  private static void record(String name) {
    calls.put(name, WorkflowUnsafe.isReadOnly());
  }

  private static void assertRandomStreamRejected() {
    ReadOnlyException error =
        assertThrows(ReadOnlyException.class, () -> Workflow.getRandomStream("io.temporal.test"));
    assertEquals("While in read-only function, action attempted: random", error.getMessage());
  }

  @WorkflowInterface
  public interface ReadOnlyWorkflow {
    @WorkflowMethod
    String run();

    @QueryMethod
    boolean query();

    @UpdateMethod
    String update();

    @UpdateValidatorMethod(updateName = "update")
    void validateUpdate();

    @SignalMethod
    void finish();
  }

  public static class ReadOnlyWorkflowImpl implements ReadOnlyWorkflow {
    private boolean finished;

    @Override
    public String run() {
      record("workflow");
      Workflow.sideEffect(boolean.class, () -> recordAndReturnTrue("sideEffect"));
      Workflow.mutableSideEffect(
          "read-only",
          boolean.class,
          Boolean::equals,
          () -> recordAndReturnTrue("mutableSideEffect"));
      Workflow.await(
          Duration.ofMillis(1),
          () -> {
            record("await");
            return true;
          });
      Workflow.getVersion("read-only", Workflow.DEFAULT_VERSION, 1);
      Workflow.await(() -> finished);
      return "done";
    }

    @Override
    public boolean query() {
      record("query");
      assertRandomStreamRejected();
      return true;
    }

    @Override
    public String update() {
      record("update");
      return "updated";
    }

    @Override
    public void validateUpdate() {
      record("validator");
      assertRandomStreamRejected();
    }

    @Override
    public void finish() {
      record("signal");
      finished = true;
    }

    private static boolean recordAndReturnTrue(String name) {
      record(name);
      return true;
    }
  }

  private static class ReadOnlyRecordingInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public WorkflowOutput execute(WorkflowInput input) {
          record("execute");
          return super.execute(input);
        }

        @Override
        public void handleSignal(SignalInput input) {
          record("handleSignal");
          super.handleSignal(input);
        }

        @Override
        public QueryOutput handleQuery(QueryInput input) {
          record("handleQuery");
          return super.handleQuery(input);
        }

        @Override
        public void validateUpdate(UpdateInput input) {
          record("validateUpdate");
          super.validateUpdate(input);
        }

        @Override
        public UpdateOutput executeUpdate(UpdateInput input) {
          record("executeUpdate");
          return super.executeUpdate(input);
        }
      };
    }
  }
}
