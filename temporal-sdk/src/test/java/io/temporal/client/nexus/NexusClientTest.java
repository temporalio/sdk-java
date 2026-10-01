package io.temporal.client.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.enums.v1.NexusOperationExecutionStatus;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.NexusClient;
import io.temporal.client.NexusOperationExecutionCount;
import io.temporal.client.NexusOperationExecutionDescription;
import io.temporal.client.NexusOperationExecutionMetadata;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UntypedNexusOperationHandle;
import io.temporal.client.UntypedNexusServiceClient;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.EchoNexusServiceImpl;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote(
    "Cloud CI does not provision the standalone Nexus endpoint required by this test.")
@Category(RequiresCloudProvisioning.class)
public class NexusClientTest {

  private final AtomicInteger activityInvocationCount = new AtomicInteger();
  private final AtomicReference<String> observedActivityId = new AtomicReference<>();
  private final AtomicReference<String> activityRunId = new AtomicReference<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(NexusClientTest.PlaceholderWorkflowImpl.class)
          .setActivityImplementations(new LinkingActivityImpl())
          .setNexusServiceImplementation(new EchoNexusServiceImpl(), new ActivityNexusServiceImpl())
          .build();

  @Before
  public void requireStandaloneNexusSupport() {
    assumeTrue(
        "server does not support standalone Nexus operations",
        testWorkflowRule.isUseExternalService());
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
  public void standaloneNexusOperationStartsActivity() throws Exception {
    Endpoint endpoint = testWorkflowRule.getNexusEndpoint();
    UntypedNexusServiceClient serviceClient =
        testWorkflowRule
            .getNexusClient()
            .newUntypedNexusServiceClient(
                endpoint.getSpec().getName(), ActivityNexusService.class.getSimpleName());

    String operationId = "operation-" + UUID.randomUUID();
    String activityId = "activity-" + UUID.randomUUID();
    UntypedNexusOperationHandle operationHandle =
        serviceClient.start(
            "operation",
            StartNexusOperationOptions.newBuilder()
                .setId(operationId)
                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                .build(),
            activityId);
    String operationRunId = operationHandle.getNexusOperationRunId();
    Assert.assertNotNull("expected SANO run id to be populated by start", operationRunId);

    Assert.assertEquals(
        "completed " + activityId, operationHandle.getResult(30, TimeUnit.SECONDS, String.class));
    Assert.assertEquals(
        "the activity should execute exactly once", 1, activityInvocationCount.get());
    Assert.assertEquals(activityId, observedActivityId.get());

    String capturedActivityRunId = activityRunId.get();
    Assert.assertNotNull(
        "expected the activity implementation to observe its run id", capturedActivityRunId);
    ActivityClient activityClient =
        ActivityClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            ActivityClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .build());
    ActivityExecutionDescription activityDescription =
        activityClient.getHandle(activityId, capturedActivityRunId).describe();
    Assert.assertEquals(activityId, activityDescription.getActivityId());
    Assert.assertEquals(capturedActivityRunId, activityDescription.getActivityRunId());
    Assert.assertEquals(
        ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_COMPLETED,
        activityDescription.getStatus());
    Assert.assertEquals(testWorkflowRule.getTaskQueue(), activityDescription.getTaskQueue());

    Link.NexusOperation forwardLink = null;
    for (Link link : activityDescription.getRawInfo().getLinksList()) {
      if (link.hasNexusOperation()) {
        forwardLink = link.getNexusOperation();
        break;
      }
    }
    Assert.assertNotNull(
        "expected Link.NexusOperation on the standalone activity execution", forwardLink);
    String namespace = testWorkflowRule.getWorkflowClient().getOptions().getNamespace();
    Assert.assertEquals(namespace, forwardLink.getNamespace());
    Assert.assertEquals(operationId, forwardLink.getOperationId());
    Assert.assertEquals(operationRunId, forwardLink.getRunId());

    NexusOperationExecutionDescription operationDescription = operationHandle.describe();
    Assert.assertEquals(operationId, operationDescription.getOperationId());
    Assert.assertEquals(operationRunId, operationDescription.getRunId());
    Assert.assertEquals(endpoint.getSpec().getName(), operationDescription.getEndpoint());
    Assert.assertEquals(
        ActivityNexusService.class.getSimpleName(), operationDescription.getService());
    Assert.assertEquals("operation", operationDescription.getOperation());
    Assert.assertEquals(
        NexusOperationExecutionStatus.NEXUS_OPERATION_EXECUTION_STATUS_COMPLETED,
        operationDescription.getStatus());

    Link.Activity backwardLink = null;
    for (Link link : operationDescription.getRawInfo().getLinksList()) {
      if (link.hasActivity() && activityId.equals(link.getActivity().getActivityId())) {
        backwardLink = link.getActivity();
        break;
      }
    }
    Assert.assertNotNull(
        "expected Link.Activity on the standalone Nexus operation execution", backwardLink);
    Assert.assertEquals(namespace, backwardLink.getNamespace());
    Assert.assertEquals(activityId, backwardLink.getActivityId());
    Assert.assertEquals(capturedActivityRunId, backwardLink.getRunId());
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

  @ActivityInterface
  public interface LinkingActivity {
    @ActivityMethod
    String execute(String activityId);
  }

  public class LinkingActivityImpl implements LinkingActivity {
    @Override
    public String execute(String activityId) {
      activityInvocationCount.incrementAndGet();
      observedActivityId.set(Activity.getExecutionContext().getInfo().getActivityId());
      activityRunId.set(Activity.getExecutionContext().getInfo().getActivityRunId());
      return "completed " + activityId;
    }
  }

  @Service
  public interface ActivityNexusService {
    @Operation
    String operation(String activityId);
  }

  @ServiceImpl(service = ActivityNexusService.class)
  public class ActivityNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return TemporalOperationHandler.create(
          (context, client, activityId) ->
              client.startActivity(
                  LinkingActivity.class,
                  LinkingActivity::execute,
                  activityId,
                  StartActivityOptions.newBuilder()
                      .setId(activityId)
                      .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                      .build()));
    }
  }
}
