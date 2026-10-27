package io.temporal.workflow.nexus;

import static io.temporal.internal.nexus.ContextRecordingCodec.contextOf;

import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.DescribeActivityOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.internal.nexus.ContextRecordingCodec;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end coverage that workflows and standalone activities started by a Nexus operation handler
 * serialize their payloads with the handler's Nexus context, and that clients reading their results
 * use the context the server returns.
 *
 * <p>Requires a server that propagates the context (temporalio/temporal#12400, #12401, #12403),
 * with {@code nexusoperation.enableStandalone} and {@code activity.enableCallbacks} enabled. The
 * dev server pinned in {@code SdkJavaTestServerProfile} includes them.
 */
@CloudTestExclusionNote("Requires a server with Nexus serialization context propagation.")
@Category(RequiresCloudProvisioning.class)
public class NexusSerializationContextPropagationTest {

  private static final ContextRecordingCodec CODEC = new ContextRecordingCodec();
  // Unique per run so a leftover execution from an earlier run can't be picked up.
  private static final String RUN_ID = UUID.randomUUID().toString();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CallerWorkflowImpl.class, BackingWorkflowImpl.class)
          .setActivityImplementations(new BackingActivityImpl())
          .setNexusServiceImplementation(new ContextServiceImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder().setDataConverter(CODEC.dataConverter()).build())
          .build();

  @Before
  public void setUp() {
    Assume.assumeTrue(
        "Nexus operations require a real server", SDKTestWorkflowRule.useExternalService);
    CODEC.reset();
  }

  @Test
  public void workflowStartedByHandlerUsesNexusContextForResult() {
    String input = "workflow-" + RUN_ID;
    Assert.assertEquals("hello " + input, newCaller().execute("startWorkflow", input));

    String expected = describe("startWorkflow");
    String workflowContext = workflowContext(backingId(input));
    WorkflowExecutionHistory history = testWorkflowRule.getExecutionHistory(backingId(input));
    HistoryEvent started = history.getEvents().get(0);
    Assert.assertEquals(
        propagated("startWorkflow"),
        started
            .getWorkflowExecutionStartedEventAttributes()
            .getPropagatedNexusSerializationContext());
    // Only the result, which is returned to the Nexus caller, uses the Nexus context.
    Assert.assertEquals(
        workflowContext,
        contextOf(started.getWorkflowExecutionStartedEventAttributes().getInput().getPayloads(0)));
    Assert.assertEquals(
        expected,
        contextOf(
            history
                .getLastEvent()
                .getWorkflowExecutionCompletedEventAttributes()
                .getResult()
                .getPayloads(0)));

    // A detached handle gets the context from the server with the history.
    CODEC.reset();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(backingId(input))
            .getResult(String.class);
    Assert.assertEquals("hello " + input, result);
    Assert.assertTrue(
        "detached handle should decode with the Nexus context, saw " + CODEC.decodeContexts(),
        CODEC.decodeContexts().contains(expected));

    // Memo from the start request and the workflow's upsert, and user metadata, keep the workflow
    // context, and describe decodes them with it.
    Assert.assertEquals(
        workflowContext,
        contextOf(
            started
                .getWorkflowExecutionStartedEventAttributes()
                .getMemo()
                .getFieldsOrThrow("started")));
    CODEC.reset();
    WorkflowExecutionDescription description =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(backingId(input)).describe();
    Assert.assertEquals(input, description.getMemo("started", String.class));
    Assert.assertEquals(input, description.getMemo("upserted", String.class));
    Assert.assertEquals("summary-" + input, description.getStaticSummary());
    Assert.assertEquals("details-" + input, description.getStaticDetails());
    for (String context : CODEC.decodeContexts()) {
      Assert.assertEquals(
          "describe should decode with the workflow context", workflowContext, context);
    }
  }

  @Test
  public void continuedAsNewRunKeepsNexusContext() {
    String input = "can-" + RUN_ID;
    Assert.assertEquals("hello " + input, newCaller().execute("startWorkflow", input));

    String expected = describe("startWorkflow");
    // Without a run ID, the history is of the latest run, the one the first run continued into.
    WorkflowExecutionHistory history = testWorkflowRule.getExecutionHistory(backingId(input));
    WorkflowExecutionStartedEventAttributes started =
        history.getEvents().get(0).getWorkflowExecutionStartedEventAttributes();
    Assert.assertFalse(
        "expected the latest run to be a continued run",
        started.getContinuedExecutionRunId().isEmpty());
    Assert.assertEquals(
        propagated("startWorkflow"), started.getPropagatedNexusSerializationContext());
    // Continue-as-new input keeps the workflow context; only the final result uses the Nexus one.
    Assert.assertEquals(
        workflowContext(backingId(input)), contextOf(started.getInput().getPayloads(0)));
    Assert.assertEquals(
        expected,
        contextOf(
            history
                .getLastEvent()
                .getWorkflowExecutionCompletedEventAttributes()
                .getResult()
                .getPayloads(0)));

    // A detached handle follows the continue-as-new chain and decodes the final result with the
    // context the server returns for the run that closed.
    CODEC.reset();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(backingId(input))
            .getResult(String.class);
    Assert.assertEquals("hello " + input, result);
    Assert.assertTrue(
        "detached handle should decode with the Nexus context, saw " + CODEC.decodeContexts(),
        CODEC.decodeContexts().contains(expected));
  }

  @Test
  public void activityStartedByHandlerUsesNexusContextForResult() throws Exception {
    String input = "activity-" + RUN_ID;
    String expected = describe("startActivity");
    String activityContext =
        ContextRecordingCodec.describe(
            new ActivitySerializationContext(
                testWorkflowRule.getWorkflowClient().getOptions().getNamespace(),
                null,
                null,
                "Process",
                testWorkflowRule.getTaskQueue(),
                false));
    ActivityClient activityClient =
        ActivityClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            ActivityClientOptions.newBuilder()
                .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
                .setDataConverter(CODEC.dataConverter())
                .build());

    BackingActivityImpl.release = new CountDownLatch(1);
    CallerWorkflow caller = newCaller();
    CompletableFuture<String> callerResult =
        WorkflowClient.execute(caller::execute, "startActivity", input);

    // The heartbeat details and the first attempt's failure are read while the second attempt
    // waits to be released, before the activity completes.
    DescribeActivityExecutionResponse running = null;
    Deadline deadline = Deadline.after(10, TimeUnit.SECONDS);
    while (!deadline.isExpired()) {
      try {
        running = describeActivity(activityClient, input);
        if (running.getInfo().hasLastFailure()) {
          break;
        }
      } catch (StatusRuntimeException e) {
        // The handler may not have started the activity yet.
        if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
          throw e;
        }
      }
      Thread.sleep(100);
    }
    Assert.assertNotNull("activity was never described", running);
    Assert.assertEquals(
        propagated("startActivity"), running.getInfo().getPropagatedNexusSerializationContext());
    Assert.assertEquals(activityContext, contextOf(running.getInput().getPayloads(0)));
    Assert.assertEquals(
        activityContext, contextOf(running.getInfo().getUserMetadata().getSummary()));
    Assert.assertEquals(
        activityContext, contextOf(running.getInfo().getHeartbeatDetails().getPayloads(0)));
    // The attempt's failure, like the result, is returned to the Nexus caller.
    Assert.assertEquals(
        expected,
        contextOf(
            running
                .getInfo()
                .getLastFailure()
                .getApplicationFailureInfo()
                .getDetails()
                .getPayloads(0)));

    BackingActivityImpl.release.countDown();
    Assert.assertEquals("hello " + input, callerResult.get(30, TimeUnit.SECONDS));
    Assert.assertEquals(
        expected,
        contextOf(describeActivity(activityClient, input).getOutcome().getResult().getPayloads(0)));

    // A handle obtained by ID gets the context from the server with the poll response.
    CODEC.reset();
    Assert.assertEquals(
        "hello " + input,
        activityClient.getHandle(backingId(input), null, String.class).getResult());
    Assert.assertTrue(
        "activity handle should decode with the Nexus context, saw " + CODEC.decodeContexts(),
        CODEC.decodeContexts().contains(expected));
  }

  private static DescribeActivityExecutionResponse describeActivity(
      ActivityClient activityClient, String input) {
    return activityClient
        .getHandle(backingId(input), null)
        .describe(
            DescribeActivityOptions.newBuilder()
                .setIncludeInput(true)
                .setIncludeOutcome(true)
                .setIncludeHeartbeatDetails(true)
                .setIncludeLastFailure(true)
                .build())
        .getRawResponse();
  }

  private CallerWorkflow newCaller() {
    return testWorkflowRule.newWorkflowStubTimeoutOptions(CallerWorkflow.class);
  }

  private String workflowContext(String workflowId) {
    return ContextRecordingCodec.describe(
        new WorkflowSerializationContext(
            testWorkflowRule.getWorkflowClient().getOptions().getNamespace(), workflowId));
  }

  private String describe(String operation) {
    return ContextRecordingCodec.describe(
        new NexusSerializationContext(
            testWorkflowRule.getNexusEndpoint().getSpec().getName(),
            ContextService.class.getSimpleName(),
            operation));
  }

  private PropagatedSerializationContext propagated(String operation) {
    return PropagatedSerializationContext.newBuilder()
        .setEndpoint(testWorkflowRule.getNexusEndpoint().getSpec().getName())
        .setService(ContextService.class.getSimpleName())
        .setOperation(operation)
        .build();
  }

  /** ID of the workflow or activity backing the operation called with {@code input}. */
  private static String backingId(String input) {
    return "backing-" + input;
  }

  @Service
  public interface ContextService {
    @Operation
    String startWorkflow(String input);

    @Operation
    String startActivity(String input);
  }

  @ServiceImpl(service = ContextService.class)
  public static class ContextServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> startWorkflow() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          BackingWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId(backingId(input))
                              .setMemo(Collections.singletonMap("started", input))
                              .setStaticSummary("summary-" + input)
                              .setStaticDetails("details-" + input)
                              .setTaskQueue(Nexus.getOperationContext().getInfo().getTaskQueue())
                              .build())
                  ::execute);
    }

    @OperationImpl
    public OperationHandler<String, String> startActivity() {
      return TemporalOperationHandler.create(
          (context, client, input) ->
              client.startActivity(
                  BackingActivity.class,
                  BackingActivity::process,
                  input,
                  StartActivityOptions.newBuilder()
                      .setId(backingId(input))
                      .setSummary("summary-" + input)
                      .setRetryOptions(
                          RetryOptions.newBuilder()
                              .setInitialInterval(Duration.ofMillis(100))
                              .build())
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .build()));
    }
  }

  @WorkflowInterface
  public interface CallerWorkflow {
    @WorkflowMethod
    String execute(String operation, String input);
  }

  public static class CallerWorkflowImpl implements CallerWorkflow {
    @Override
    public String execute(String operation, String input) {
      ContextService service =
          Workflow.newNexusServiceStub(
              ContextService.class,
              NexusServiceOptions.newBuilder()
                  .setOperationOptions(
                      NexusOperationOptions.newBuilder()
                          .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                          .build())
                  .build());
      try {
        return "startWorkflow".equals(operation)
            ? service.startWorkflow(input)
            : service.startActivity(input);
      } catch (NexusOperationFailure e) {
        return "failed: " + e.getCause().getMessage();
      }
    }
  }

  @WorkflowInterface
  public interface BackingWorkflow {
    @WorkflowMethod
    String execute(String input);
  }

  public static class BackingWorkflowImpl implements BackingWorkflow {
    @Override
    public String execute(String input) {
      Workflow.upsertMemo(Collections.singletonMap("upserted", input));
      // Continue as new once, so the result comes from the second run.
      if (input.startsWith("can-")
          && !Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
        Workflow.continueAsNew(input);
      }
      return "hello " + input;
    }
  }

  @ActivityInterface
  public interface BackingActivity {
    @ActivityMethod
    String process(String input);
  }

  public static class BackingActivityImpl implements BackingActivity {
    // Holds the second attempt until the test has described the running activity.
    static volatile CountDownLatch release = new CountDownLatch(0);

    @Override
    public String process(String input) {
      // The first attempt fails after heartbeating, so the worker sends the heartbeat details with
      // the failure, and the server keeps them along with the attempt's failure.
      if (Activity.getExecutionContext().getInfo().getAttempt() == 1) {
        Activity.getExecutionContext().heartbeat("heartbeat-" + input);
        throw ApplicationFailure.newFailure("first attempt fails", "Retry", "detail-" + input);
      }
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return "hello " + input;
    }
  }
}
