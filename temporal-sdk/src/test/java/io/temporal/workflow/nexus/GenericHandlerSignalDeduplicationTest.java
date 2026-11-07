package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.TemporalNexusClient;
import io.temporal.nexus.TemporalOperation;
import io.temporal.nexus.TemporalOperationResult;
import io.temporal.nexus.TemporalOperationStartContext;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Signals sent through {@link TemporalNexusClient#getWorkflowClient()} reuse the operation's
 * request ID, so only one signal is allowed per operation invocation and a retried Nexus task does
 * not deliver the signal twice.
 */
@CloudTestExclusionNote("Cloud CI does not provision the Nexus endpoint required by this test.")
@Category(RequiresCloudProvisioning.class)
public class GenericHandlerSignalDeduplicationTest {
  private static final String ONE_SIGNAL_MESSAGE =
      "Only one signal can be sent per Nexus operation invocation.";
  private static final Map<String, List<String>> retryRequestIds = new ConcurrentHashMap<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CallerWorkflowImpl.class, CountSignalsWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void secondSignalRejected() {
    String targetId = "double-signal-" + UUID.randomUUID();
    CountSignalsWorkflow target =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                CountSignalsWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(targetId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .build());
    WorkflowClient.start(target::execute);

    Assert.assertEquals("signaled-" + targetId, newCaller().execute("doubleSignal", targetId));

    target.done();
    Assert.assertEquals(1, WorkflowStub.fromTyped(target).getResult(Integer.class).intValue());
  }

  @Test
  public void signalDedupedAcrossHandlerRetry() {
    // The in-memory test server does not dedupe signals on request ID.
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    String targetId = "signal-retry-" + UUID.randomUUID();

    Assert.assertEquals("signaled-" + targetId, newCaller().execute("signalRetry", targetId));

    List<String> requestIds = retryRequestIds.get(targetId);
    Assert.assertEquals(
        "handler should fail once and then be redelivered once", 2, requestIds.size());
    Assert.assertEquals(requestIds.get(0), requestIds.get(1));

    CountSignalsWorkflow target =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(CountSignalsWorkflow.class, targetId);
    target.done();
    Assert.assertEquals(
        "signal must be deduped on the operation's request ID across handler redelivery",
        1,
        WorkflowStub.fromTyped(target).getResult(Integer.class).intValue());
  }

  private CallerWorkflow newCaller() {
    return testWorkflowRule.newWorkflowStubTimeoutOptions(CallerWorkflow.class);
  }

  @WorkflowInterface
  public interface CallerWorkflow {
    @WorkflowMethod
    String execute(String operation, String targetId);
  }

  public static class CallerWorkflowImpl implements CallerWorkflow {
    @Override
    public String execute(String operation, String targetId) {
      TestNexusService service =
          Workflow.newNexusServiceStub(
              TestNexusService.class,
              NexusServiceOptions.newBuilder()
                  .setOperationOptions(
                      NexusOperationOptions.newBuilder()
                          .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                          .build())
                  .build());
      switch (operation) {
        case "doubleSignal":
          return service.doubleSignal(targetId);
        case "signalRetry":
          return service.signalRetry(targetId);
        default:
          throw new IllegalArgumentException("unknown operation " + operation);
      }
    }
  }

  @WorkflowInterface
  public interface CountSignalsWorkflow {
    @WorkflowMethod
    int execute();

    @SignalMethod
    void mySignal(String value);

    @SignalMethod
    void done();
  }

  /** Returns how many {@code mySignal} deliveries reached the workflow before {@code done}. */
  public static class CountSignalsWorkflowImpl implements CountSignalsWorkflow {
    private int count;
    private boolean done;

    @Override
    public int execute() {
      Workflow.await(() -> done);
      return count;
    }

    @Override
    public void mySignal(String value) {
      count++;
    }

    @Override
    public void done() {
      done = true;
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    String doubleSignal(String targetId);

    @Operation
    String signalRetry(String targetId);
  }

  @ServiceImpl(service = TestNexusService.class)
  public static class TestNexusServiceImpl {
    @TemporalOperation
    public TemporalOperationResult<String> doubleSignal(
        TemporalOperationStartContext context, TemporalNexusClient client, String targetId) {
      client.getWorkflowClient().newUntypedWorkflowStub(targetId).signal("mySignal", targetId);
      // The first signal uses up the invocation's only signal, so a second one is rejected even
      // though it targets a different workflow.
      try {
        client
            .getWorkflowClient()
            .newUntypedWorkflowStub(targetId + "-other")
            .signal("mySignal", targetId);
      } catch (HandlerException e) {
        if (e.getErrorType() == HandlerException.ErrorType.BAD_REQUEST
            && ONE_SIGNAL_MESSAGE.equals(e.getCause().getMessage())) {
          return TemporalOperationResult.sync("signaled-" + targetId);
        }
        throw e;
      }
      throw new IllegalStateException("expected second signal to be rejected");
    }

    @TemporalOperation
    public TemporalOperationResult<String> signalRetry(
        TemporalOperationStartContext context, TemporalNexusClient client, String targetId) {
      client
          .getWorkflowClient()
          .newUntypedWorkflowStub(
              "CountSignalsWorkflow",
              WorkflowOptions.newBuilder()
                  .setWorkflowId(targetId)
                  .setTaskQueue(Nexus.getOperationContext().getInfo().getTaskQueue())
                  .build())
          .signalWithStart("mySignal", new Object[] {targetId}, new Object[] {});

      List<String> requestIds =
          retryRequestIds.computeIfAbsent(targetId, k -> new CopyOnWriteArrayList<>());
      requestIds.add(context.getRequestId());
      if (requestIds.size() == 1) {
        throw new HandlerException(
            HandlerException.ErrorType.INTERNAL,
            "force handler redelivery after signalling",
            null,
            HandlerException.RetryBehavior.RETRYABLE);
      }
      return TemporalOperationResult.sync("signaled-" + targetId);
    }
  }
}
