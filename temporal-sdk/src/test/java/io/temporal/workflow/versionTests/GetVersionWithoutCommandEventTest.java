package io.temporal.workflow.versionTests;

import static io.temporal.internal.history.VersionMarkerUtils.TEMPORAL_CHANGE_VERSION;
import static org.junit.Assert.assertNull;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestSignaledWorkflow;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionWithoutCommandEventTest extends BaseVersionTest {

  private static CompletableFuture<Boolean> executionStarted = new CompletableFuture<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              getDefaultWorkflowImplementationOptions(),
              TestGetVersionWithoutCommandEventWorkflowImpl.class)
          // Forcing a replay. Full history arrived from a normal queue causing a replay.
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  public GetVersionWithoutCommandEventTest(boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testGetVersionWithoutCommandEvent() throws Exception {
    executionStarted = new CompletableFuture<Boolean>();
    TestSignaledWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestSignaledWorkflow.class);
    WorkflowClient.start(workflowStub::execute);
    executionStarted.get();
    workflowStub.signal("test signal");
    String result = WorkflowStub.fromTyped(workflowStub).getResult(String.class);
    Assert.assertEquals("result 1", result);

    List<String> versions =
        WorkflowStub.fromTyped(workflowStub)
            .describe()
            .getTypedSearchAttributes()
            .get(TEMPORAL_CHANGE_VERSION);
    assertNull(versions);
  }

  public static class TestGetVersionWithoutCommandEventWorkflowImpl
      implements TestSignaledWorkflow {

    CompletablePromise<Boolean> signalReceived = Workflow.newPromise();

    @Override
    public String execute() {
      try {
        if (!WorkflowUnsafe.isReplaying()) {
          executionStarted.complete(true);
          signalReceived.get();
        } else {
          // Execute getVersion in replay mode. In this case we have no command event, only a
          // signal.
          int version = Workflow.getVersion("test_change", Workflow.DEFAULT_VERSION, 1);
          if (version == Workflow.DEFAULT_VERSION) {
            signalReceived.get();
            return "result 1";
          } else {
            return "result 2";
          }
        }
        Workflow.sleep(1000);
      } catch (Exception e) {
        throw new RuntimeException("failed to get from signal");
      }

      throw new RuntimeException("unreachable");
    }

    @Override
    public void signal(String arg) {
      signalReceived.complete(true);
    }
  }
}
