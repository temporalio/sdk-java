package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationExecutionCount;
import io.temporal.client.NexusOperationExecutionMetadata;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class NexusClientTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(NexusClientTest.PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @BeforeClass
  public static void requireExternalService() {
    // The time-skipping test server does not implement standalone Nexus operation RPCs.
    assumeTrue(
        "standalone Nexus operations require a real server",
        SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void listNexusOperationExecutions() {
    // Just run a basic test to see if it works
    // runStandaloneNexusOperation tests this more thoroughly
    NexusClient client = testWorkflowRule.getNexusClient();

    // Materialize the lazy stream to force at least one page fetch and ensure no exceptions.
    long visited = client.listNexusOperationExecutions(null).count();

    Assert.assertTrue("expected a non-negative count of listed operations", visited >= 0);
  }

  @Test
  public void countNexusOperationExecutions() {
    // Just run a basic test to see if it works
    // runStandaloneNexusOperation tests this more thoroughly
    countNexusOperations();
  }

  // A helper function to get the count and do a few validation tests around it
  public long countNexusOperations() {
    NexusClient client = testWorkflowRule.getNexusClient();

    NexusOperationExecutionCount output = client.countNexusOperationExecutions(null);

    Assert.assertNotNull(output);
    Assert.assertTrue(output.getCount() >= 0);
    Assert.assertNotNull(output.getGroups());

    return output.getCount();
  }

  @Test
  public void runStandaloneNexusOperation() throws Exception {
    long initialCount = countNexusOperations();

    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    String inputValue = "ping-" + UUID.randomUUID();
    NexusClient client = testWorkflowRule.getNexusClient();

    UntypedNexusServiceClient svcClient =
        client.newUntypedNexusServiceClient(
            endpoint.getSpec().getName(),
            TestNexusServices.TestNexusService1.class.getSimpleName());
    StartNexusOperationOptions opts =
        StartNexusOperationOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .build();
    UntypedNexusOperationHandle handle = svcClient.start("operation", opts, inputValue);
    String operationId = handle.getNexusOperationId();

    // Block on the handle until the operation completes; the echoed result implies the
    // handler received our input.
    String result = handle.getResult(60, TimeUnit.SECONDS, String.class);
    Assert.assertEquals("echo:" + inputValue, result);

    // Poll the list until our operationId appears. This also tests that the list operation
    // works correctly.
    NexusOperationExecutionMetadata listed =
        waitForListedOperation(client, operationId, Duration.ofSeconds(15));
    Assert.assertNotNull(
        "expected operationId " + operationId + " to appear in listNexusOperationExecutions",
        listed);
    Assert.assertEquals(operationId, listed.getOperationId());
    Assert.assertEquals(endpoint.getSpec().getName(), listed.getEndpoint());
    Assert.assertEquals(
        TestNexusServices.TestNexusService1.class.getSimpleName(), listed.getService());
    Assert.assertEquals("operation", listed.getOperation());
    // Make sure the count went up.
    Assert.assertTrue(countNexusOperations() > initialCount);
  }

  private NexusOperationExecutionMetadata waitForListedOperation(
      NexusClient client, String operationId, Duration timeout) throws InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      NexusOperationExecutionMetadata match =
          client
              .listNexusOperationExecutions(null)
              .filter(m -> operationId.equals(m.getOperationId()))
              .findFirst()
              .orElse(null);
      if (match != null) {
        return match;
      }
      Thread.sleep(500);
    }
    return null;
  }

  public static class PlaceholderWorkflowImpl implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      return input;
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (context, details, input) -> "echo:" + (input == null ? "<null>" : input));
    }
  }
}
