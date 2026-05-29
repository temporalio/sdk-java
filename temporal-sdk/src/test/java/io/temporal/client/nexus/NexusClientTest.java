package io.temporal.client.nexus;

import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationExecutionCount;
import io.temporal.client.NexusOperationExecutionMetadata;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.StandaloneNexusTestPrerequisites;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class NexusClientTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(NexusClientTest.PlaceholderWorkflowImpl.class)
          .setNexusServiceImplementation(new EchoNexusServiceImpl())
          .build();

  @BeforeClass
  public static void requireServerWithStandaloneNexusSupport() {
    StandaloneNexusTestPrerequisites.requireServerSupport();
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
            .setId(UUID.randomUUID().toString())
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

  @Test
  public void listNexusOperationExecutionsWithQueryFiltersResults() throws Exception {
    // Run a known operation through to completion, then assert that an OperationId-scoped query
    // narrows the list to exactly that one row. Uses a built-in visibility field (OperationId), so
    // the async search-attribute registration race that affects custom SAs doesn't apply.
    String operationId = startAndAwaitSyncOperation("list-query");
    NexusClient client = testWorkflowRule.getNexusClient();

    // Sync on the unfiltered list first so the visibility index has indexed our operation; the
    // filtered query reads from the same index.
    Assert.assertNotNull(
        "expected operation to appear in visibility before filtered query",
        waitForListedOperation(client, operationId, Duration.ofSeconds(15)));

    String query = "OperationId='" + operationId + "'";
    List<NexusOperationExecutionMetadata> results =
        client.listNexusOperationExecutions(query).collect(Collectors.toList());

    // OperationId is unique server-side, so the filter must produce exactly one row — proving the
    // query string actually narrowed results rather than being a no-op passthrough.
    Assert.assertEquals("expected exactly one match for query: " + query, 1, results.size());
    Assert.assertEquals(operationId, results.get(0).getOperationId());
  }

  @Test
  public void countNexusOperationExecutionsWithQueryFiltersResults() throws Exception {
    String operationId = startAndAwaitSyncOperation("count-query");
    NexusClient client = testWorkflowRule.getNexusClient();

    Assert.assertNotNull(
        "expected operation to appear in visibility before filtered count",
        waitForListedOperation(client, operationId, Duration.ofSeconds(15)));

    String query = "OperationId='" + operationId + "'";
    NexusOperationExecutionCount count = client.countNexusOperationExecutions(query);

    Assert.assertEquals("expected exactly one match for query: " + query, 1L, count.getCount());
  }

  /**
   * Starts a sync echo operation with a unique input, blocks until it completes, and returns the
   * operation ID. Used by the filtered list/count tests to obtain a known operation to query for.
   */
  private String startAndAwaitSyncOperation(String label) throws Exception {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient svcClient =
        testWorkflowRule
            .getNexusClient()
            .newUntypedNexusServiceClient(
                endpoint.getSpec().getName(),
                TestNexusServices.TestNexusService1.class.getSimpleName());
    StartNexusOperationOptions opts =
        StartNexusOperationOptions.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setScheduleToCloseTimeout(Duration.ofSeconds(30))
            .build();
    UntypedNexusOperationHandle handle =
        svcClient.start("operation", opts, label + "-" + UUID.randomUUID());
    handle.getResult(60, TimeUnit.SECONDS, String.class);
    return handle.getNexusOperationId();
  }

  @Test
  public void untypedExecuteByClassReturnsResult() {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient svcClient =
        testWorkflowRule
            .getNexusClient()
            .newUntypedNexusServiceClient(
                endpoint.getSpec().getName(),
                TestNexusServices.TestNexusService1.class.getSimpleName());

    String result =
        svcClient.execute(
            "operation",
            String.class,
            StartNexusOperationOptions.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                .build(),
            "untyped-exec");

    Assert.assertEquals("echo:untyped-exec", result);
  }

  @Test
  public void untypedExecuteByClassAndTypeReturnsResult() {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient svcClient =
        testWorkflowRule
            .getNexusClient()
            .newUntypedNexusServiceClient(
                endpoint.getSpec().getName(),
                TestNexusServices.TestNexusService1.class.getSimpleName());

    // The Type overload exists for generic results (e.g. List<String>); exercising it with the same
    // class/type here proves the path is wired through to the data converter.
    String result =
        svcClient.execute(
            "operation",
            String.class,
            String.class,
            StartNexusOperationOptions.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                .build(),
            "untyped-exec-typed");

    Assert.assertEquals("echo:untyped-exec-typed", result);
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
}
