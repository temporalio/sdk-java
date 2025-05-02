package io.temporal.workflow;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.Test1ArgWorkflowFunc;
import io.temporal.workflow.shared.TestMultiArgWorkflowFunctions.TestMultiArgWorkflowImpl;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowIdReusePolicyTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestMultiArgWorkflowImpl.class).build();

  @Test
  public void testWorkflowIdResuePolicy() {
    // When WorkflowIdReusePolicy is not AllowDuplicate the semantics is to get result for the
    // previous run.
    String workflowId = UUID.randomUUID().toString();
    WorkflowOptions workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
            .setWorkflowId(workflowId)
            .build();
    Test1ArgWorkflowFunc stubF1_1 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test1ArgWorkflowFunc.class, workflowOptions);
    Assert.assertEquals("1", stubF1_1.func1("1"));
    Test1ArgWorkflowFunc stubF1_2 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test1ArgWorkflowFunc.class, workflowOptions);
    Assert.assertEquals("1", stubF1_2.func1("2"));

    // Setting WorkflowIdReusePolicy to AllowDuplicate will trigger new run.
    workflowOptions =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .setWorkflowId(workflowId)
            .build();
    Test1ArgWorkflowFunc stubF1_3 =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(Test1ArgWorkflowFunc.class, workflowOptions);
    Assert.assertEquals("2", stubF1_3.func1("2"));

    // Setting WorkflowIdReusePolicy to RejectDuplicate or AllowDuplicateFailedOnly does not work as
    // expected. See https://github.com/uber/cadence-java-client/issues/295.
  }
}
