package io.temporal.workflow.nexus;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;

import com.google.common.collect.ImmutableMap;
import com.uber.m3.tally.RootScopeBuilder;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.experimental.categories.Category;

/**
 * Verifies that a caller workflow sees input a handler cannot deserialize as a non-retryable {@link
 * HandlerException.ErrorType#BAD_REQUEST} rather than as a retryable internal error that is retried
 * until the operation's schedule-to-close timeout.
 */
@CloudTestExclusionNote("Cloud CI does not provision the Nexus endpoint required by this test.")
@Category(RequiresCloudProvisioning.class)
public class OperationInputDeserializationFailTest {
  private static final AtomicInteger operationInvocations = new AtomicInteger();

  private final TestStatsReporter reporter = new TestStatsReporter();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setMetricsScope(
              new RootScopeBuilder()
                  .reporter(reporter)
                  .reportEvery(com.uber.m3.util.Duration.ofMillis(10)))
          .build();

  // Check if we're forcing old format via system property
  private static boolean isUsingNewFormat() {
    return !("true".equalsIgnoreCase(System.getProperty("temporal.nexus.forceOldFailureFormat")));
  }

  @Before
  public void setUp() {
    operationInvocations.set(0);
  }

  @Test
  public void inputDeserializationFailureIsNonRetryableBadRequest() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class,
            () -> workflowStub.execute(testWorkflowRule.getNexusEndpoint().getSpec().getName()));

    Assert.assertTrue(workflowException.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) workflowException.getCause();
    Assert.assertEquals("TestNexusService2", nexusFailure.getService());
    Assert.assertEquals("operation", nexusFailure.getOperation());

    // The caller sees the handler error itself. Had it stayed retryable, the operation would have
    // been retried until the schedule-to-close timeout and surfaced as a timeout instead.
    Assert.assertTrue(nexusFailure.getCause() instanceof HandlerException);
    HandlerException handlerFailure = (HandlerException) nexusFailure.getCause();
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, handlerFailure.getErrorType());
    Assert.assertFalse(handlerFailure.isRetryable());
    if (isUsingNewFormat()) {
      Assert.assertTrue(handlerFailure.getMessage().contains("failed to deserialize input"));
    }

    // Input is deserialized before the operation handler runs, so user code is never reached.
    Assert.assertEquals(0, operationInvocations.get());

    // Reported under the BAD_REQUEST failure type. The no-retry guarantee is the assertion above
    // that the caller got the handler error rather than a timeout, since assertEventually returns
    // on the first successful evaluation and would not see a later attempt.
    Map<String, String> execFailedTags =
        ImmutableMap.<String, String>builder()
            .putAll(MetricsTag.defaultTags(NAMESPACE))
            .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.NEXUS_WORKER.getValue())
            .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue())
            .put(MetricsTag.NEXUS_SERVICE, "TestNexusService2")
            .put(MetricsTag.NEXUS_OPERATION, "operation")
            .put(
                MetricsTag.TASK_FAILURE_TYPE,
                MetricsTag.TASK_FAILURE_VALUE_HANDLER_ERROR_BAD_REQUEST)
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1));
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String endpoint) {
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(endpoint)
              .setOperationOptions(
                  NexusOperationOptions.newBuilder()
                      .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                      .build())
              .build();
      // An untyped stub lets the caller send an input the handler cannot deserialize, the way a
      // caller built against an incompatible version of the service would.
      NexusServiceStub serviceStub =
          Workflow.newUntypedNexusServiceStub("TestNexusService2", serviceOptions);
      // The operation takes an Integer.
      return serviceStub.execute("operation", String.class, "not an integer");
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService2.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Integer, Integer> operation() {
      return OperationHandler.sync(
          (ctx, details, i) -> {
            operationInvocations.incrementAndGet();
            return i;
          });
    }
  }
}
