package io.temporal.client.nexus;

import io.temporal.client.NexusClient;
import io.temporal.client.NexusClientImpl;
import io.temporal.client.NexusClientInterceptor;
import io.temporal.client.NexusClientOperationOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusClientTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(NexusClientTest.PlaceholderWorkflowImpl.class)
          .build();

  private NexusClient createNexusClient() {
    return NexusClientImpl.newInstance(
        testWorkflowRule.getWorkflowServiceStubs(),
        NexusClientOperationOptions.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .build());
  }

  @Test
  public void listNexusOperationExecutions() {
    NexusClient client = createNexusClient();
    NexusClientInterceptor.ListNexusOperationExecutionsInput input =
        new NexusClientInterceptor.ListNexusOperationExecutionsInput(null, 100, null);

    NexusClientInterceptor.ListNexusOperationExecutionsOutput output =
        client.listNexusOperationExecutions(input);

    Assert.assertNotNull(output);
    Assert.assertNotNull(output.getOperations());
    Assert.assertNotNull(output.getNextPageToken());
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }
}
