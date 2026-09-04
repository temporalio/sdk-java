package io.temporal.worker;

import static org.junit.Assume.assumeTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.namespace.v1.NamespaceInfo.Capabilities;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowTaskCompletionPaginationIntegrationTest {

  // Six 1 MiB activity inputs scheduled in a single workflow task produce a ~6 MiB completion, well
  // over the ~4 MiB gRPC request limit, so the workflow completes only if the completion is
  // paginated.
  private static final int ACTIVITY_COUNT = 6;
  private static final int ACTIVITY_INPUT_BYTES = 1024 * 1024;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(LargeCompletionWorkflowImpl.class)
          .setActivityImplementations(new NoopActivityImpl())
          .build();

  @Test
  public void largeCompletionIsPaginated() {
    assumeTrue(
        "Requires a real server with workflow task completion pagination support",
        SDKTestWorkflowRule.useExternalService);
    assumeTrue(
        "Server does not support workflow task completion pagination",
        getNamespaceCapabilities().getWorkflowTaskCompletionPagination());

    LargeCompletionWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                LargeCompletionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                    .build());
    // Completes without error only when the oversized completion is delivered across pages.
    workflow.run();
  }

  private Capabilities getNamespaceCapabilities() {
    DescribeNamespaceResponse response =
        testWorkflowRule
            .getWorkflowClient()
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeNamespace(
                DescribeNamespaceRequest.newBuilder()
                    .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                    .build());
    return response.getNamespaceInfo().getCapabilities();
  }

  @WorkflowInterface
  public interface LargeCompletionWorkflow {
    @WorkflowMethod
    void run();
  }

  public static class LargeCompletionWorkflowImpl implements LargeCompletionWorkflow {
    private final NoopActivity activity =
        Workflow.newActivityStub(
            NoopActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());

    @Override
    public void run() {
      byte[] input = new byte[ACTIVITY_INPUT_BYTES];
      List<Promise<Void>> promises = new ArrayList<>(ACTIVITY_COUNT);
      for (int i = 0; i < ACTIVITY_COUNT; i++) {
        promises.add(Async.procedure(activity::process, input));
      }
      Promise.allOf(promises).get();
    }
  }

  @ActivityInterface
  public interface NoopActivity {
    @ActivityMethod
    void process(byte[] input);
  }

  public static class NoopActivityImpl implements NoopActivity {
    @Override
    public void process(byte[] input) {}
  }
}
