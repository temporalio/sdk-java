package io.temporal.workflow;

import static io.temporal.testing.internal.SDKTestOptions.newWorkflowOptionsWithTimeouts;
import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowStaticSummaryTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowReadingStaticMetadata.class)
          .build();

  static final String SUMMARY = "my-static-summary";
  static final String DETAILS = "my-static-details";

  @Test
  public void testGetStaticSummaryAndDetails() {
    WorkflowOptions options =
        newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setStaticSummary(SUMMARY)
            .setStaticDetails(DETAILS)
            .build();
    TestStaticMetadataWorkflow stub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestStaticMetadataWorkflow.class, options);

    String result = stub.execute();
    assertEquals(SUMMARY + "|" + DETAILS, result);
  }

  @Test
  public void testGetStaticSummaryAndDetailsWhenNotSet() {
    WorkflowOptions options = newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue());
    TestStaticMetadataWorkflow stub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestStaticMetadataWorkflow.class, options);

    String result = stub.execute();
    assertEquals("null|null", result);
  }

  @WorkflowInterface
  public interface TestStaticMetadataWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class WorkflowReadingStaticMetadata implements TestStaticMetadataWorkflow {
    @Override
    public String execute() {
      return Workflow.getStaticSummary() + "|" + Workflow.getStaticDetails();
    }
  }
}
