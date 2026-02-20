package io.temporal.workflow.nexus;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;

import com.google.common.collect.ImmutableMap;
import com.uber.m3.tally.RootScopeBuilder;
import io.nexusrpc.OperationException;
import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.MetricsType;
import io.temporal.worker.WorkerMetricsTag;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class OperationFailMetricTest {
  private static final Map<String, Integer> invocationCount = new ConcurrentHashMap<>();

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

  private ImmutableMap.Builder<String, String> getBaseTags() {
    return ImmutableMap.<String, String>builder()
        .putAll(MetricsTag.defaultTags(NAMESPACE))
        .put(MetricsTag.WORKER_TYPE, WorkerMetricsTag.WorkerType.NEXUS_WORKER.getValue())
        .put(MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue());
  }

  private ImmutableMap.Builder<String, String> getOperationTags() {
    return getBaseTags()
        .put(MetricsTag.NEXUS_SERVICE, "TestNexusService1")
        .put(MetricsTag.NEXUS_OPERATION, "operation");
  }

  private <T extends Throwable> T assertNexusOperationFailure(
      Class<T> expectedCause, WorkflowFailedException workflowException) {
    Assert.assertTrue(workflowException.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusOperationFailure =
        (NexusOperationFailure) workflowException.getCause();
    Assert.assertEquals(
        testWorkflowRule.getNexusEndpoint().getSpec().getName(),
        nexusOperationFailure.getEndpoint());
    Assert.assertEquals("TestNexusService1", nexusOperationFailure.getService());
    Assert.assertEquals("operation", nexusOperationFailure.getOperation());
    Assert.assertEquals("", nexusOperationFailure.getOperationToken());
    Assert.assertTrue(expectedCause.isInstance(nexusOperationFailure.getCause()));
    return expectedCause.cast(nexusOperationFailure.getCause());
  }

  @Test
  public void failOperationMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    WorkflowFailedException workflowException =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("fail"));
    assertNoRetries("fail");
    ApplicationFailure applicationFailure =
        assertNexusOperationFailure(ApplicationFailure.class, workflowException);
    if (isUsingNewFormat()) {
      Assert.assertEquals(
          "java.lang.RuntimeException: intentional failure",
          applicationFailure.getOriginalMessage());
      applicationFailure = (ApplicationFailure) applicationFailure.getCause();
    }
    Assert.assertEquals("intentional failure", applicationFailure.getOriginalMessage());

    Map<String, String> execFailedTags =
        getOperationTags().put(MetricsTag.TASK_FAILURE_TYPE, "operation_failed").buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void cancelOperationMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    WorkflowFailedException workflowException =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("cancel"));
    assertNoRetries("cancel");
    CanceledFailure canceledFailure =
        assertNexusOperationFailure(CanceledFailure.class, workflowException);
    TemporalFailure temporalFailure = canceledFailure;
    if (isUsingNewFormat()) {
      Assert.assertEquals(
          "java.lang.RuntimeException: intentional cancel", temporalFailure.getOriginalMessage());
      Assert.assertNotNull(canceledFailure.getCause());
      Assert.assertTrue(canceledFailure.getCause() instanceof ApplicationFailure);
      temporalFailure = (TemporalFailure) temporalFailure.getCause();
    }
    Assert.assertEquals("intentional cancel", temporalFailure.getOriginalMessage());

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "operation_canceled")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failOperationApplicationErrorMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    WorkflowFailedException workflowException =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("fail-app"));
    assertNoRetries("fail-app");
    ApplicationFailure applicationFailure =
        assertNexusOperationFailure(ApplicationFailure.class, workflowException);
    if (isUsingNewFormat()) {
      Assert.assertEquals(
          "io.temporal.failure.ApplicationFailure: message='intentional failure', type='TestFailure', nonRetryable=false",
          applicationFailure.getOriginalMessage());
      Assert.assertEquals("OperationError", applicationFailure.getType());
      Assert.assertNotNull(applicationFailure.getCause());
      applicationFailure = (ApplicationFailure) applicationFailure.getCause();
    }
    Assert.assertEquals("intentional failure", applicationFailure.getOriginalMessage());
    Assert.assertEquals("TestFailure", applicationFailure.getType());
    Assert.assertEquals("foo", applicationFailure.getDetails().get(String.class));

    Map<String, String> execFailedTags =
        getOperationTags().put(MetricsTag.TASK_FAILURE_TYPE, "operation_failed").buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void cancelOperationApplicationErrorMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("cancel-app"));
    assertNoRetries("cancel-app");
    CanceledFailure canceledFailure =
        assertNexusOperationFailure(CanceledFailure.class, workflowException);
    if (isUsingNewFormat()) {
      Assert.assertEquals(
          "io.temporal.failure.ApplicationFailure: message='intentional cancel', type='TestFailure', nonRetryable=false",
          canceledFailure.getOriginalMessage());
      Assert.assertEquals(0, canceledFailure.getDetails().getSize());
      Assert.assertNotNull(canceledFailure.getCause());
      Assert.assertTrue(canceledFailure.getCause() instanceof ApplicationFailure);
      ApplicationFailure applicationFailure = (ApplicationFailure) canceledFailure.getCause();
      Assert.assertEquals("TestFailure", applicationFailure.getType());
      Assert.assertEquals("intentional cancel", applicationFailure.getOriginalMessage());
      Assert.assertEquals("foo", applicationFailure.getDetails().get(String.class));
    } else {
      Assert.assertEquals("intentional cancel", canceledFailure.getOriginalMessage());
      Assert.assertEquals(1, canceledFailure.getDetails().getSize());
    }

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "operation_canceled")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failOperationMessageApplicationErrorMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);

    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("fail-msg-app"));
    assertNoRetries("fail-msg-app");
    ApplicationFailure applicationFailure =
        assertNexusOperationFailure(ApplicationFailure.class, workflowException);
    if (isUsingNewFormat()) {
      Assert.assertEquals("failure message", applicationFailure.getOriginalMessage());
      Assert.assertEquals("OperationError", applicationFailure.getType());
      applicationFailure = (ApplicationFailure) applicationFailure.getCause();
    }
    Assert.assertEquals("intentional failure", applicationFailure.getOriginalMessage());
    Assert.assertEquals("TestFailure", applicationFailure.getType());
    Assert.assertEquals("foo", applicationFailure.getDetails().get(String.class));

    Map<String, String> execFailedTags =
        getOperationTags().put(MetricsTag.TASK_FAILURE_TYPE, "operation_failed").buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerBadRequestMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("handlererror"));
    assertNoRetries("handlererror");
    HandlerException handlerException =
        assertNexusOperationFailure(HandlerException.class, workflowException);
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, handlerException.getErrorType());
    Assert.assertTrue(handlerException.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) handlerException.getCause();
    Assert.assertEquals("handlererror", applicationFailure.getOriginalMessage());

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_BAD_REQUEST")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerBadRequestNoCauseMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("handlererror-no-cause"));
    assertNoRetries("handlererror-no-cause");
    HandlerException handlerException =
        assertNexusOperationFailure(HandlerException.class, workflowException);
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, handlerException.getErrorType());
    if (isUsingNewFormat()) {
      Assert.assertEquals("handler failure message", handlerException.getMessage());
      Assert.assertNull(handlerException.getCause());
    }

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_BAD_REQUEST")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerAppBadRequestMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("handlererror-app"));
    assertNoRetries("handlererror-app");
    HandlerException handlerException =
        assertNexusOperationFailure(HandlerException.class, workflowException);
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, handlerException.getErrorType());
    Assert.assertTrue(handlerException.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) handlerException.getCause();
    Assert.assertEquals("intentional failure", applicationFailure.getOriginalMessage());
    Assert.assertEquals("TestFailure", applicationFailure.getType());
    Assert.assertEquals("foo", applicationFailure.getDetails().get(String.class));

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_BAD_REQUEST")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerMessageAppBadRequestMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("handlererror-msg-app"));
    assertNoRetries("handlererror-msg-app");
    HandlerException handlerException =
        assertNexusOperationFailure(HandlerException.class, workflowException);
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, handlerException.getErrorType());
    if (isUsingNewFormat()) {
      Assert.assertEquals("handler failure message", handlerException.getMessage());
    } else {
      Assert.assertEquals("intentional failure", handlerException.getMessage());
    }
    Assert.assertTrue(handlerException.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) handlerException.getCause();
    Assert.assertEquals("intentional failure", applicationFailure.getOriginalMessage());
    Assert.assertEquals("TestFailure", applicationFailure.getType());
    Assert.assertEquals("foo", applicationFailure.getDetails().get(String.class));

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_BAD_REQUEST")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerAlreadyStartedMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("already-started"));
    assertNoRetries("already-started");
    HandlerException handlerException =
        assertNexusOperationFailure(HandlerException.class, workflowException);
    Assert.assertEquals(HandlerException.ErrorType.BAD_REQUEST, handlerException.getErrorType());
    Assert.assertTrue(handlerException.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) handlerException.getCause();
    Assert.assertEquals(
        "io.temporal.client.WorkflowExecutionAlreadyStarted", applicationFailure.getType());

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_BAD_REQUEST")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerRetryableApplicationFailureMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    Assert.assertThrows(
        WorkflowFailedException.class, () -> workflowStub.execute("retryable-application-failure"));
    Assert.assertTrue(invocationCount.get("retryable-application-failure") > 1);

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_INTERNAL")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(
              MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, c -> c >= 1);
        });
  }

  @Test
  public void failHandlerNonRetryableApplicationFailureMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class,
            () -> workflowStub.execute("non-retryable-application-failure"));
    HandlerException handlerFailure =
        assertNexusOperationFailure(HandlerException.class, workflowException);
    assertNoRetries("non-retryable-application-failure");

    Exception failure = handlerFailure;
    if (isUsingNewFormat()) {
      Assert.assertEquals(
          "Handler failed with non-retryable application error", handlerFailure.getMessage());
      Assert.assertEquals(HandlerException.ErrorType.INTERNAL, handlerFailure.getErrorType());
      Assert.assertEquals(
          HandlerException.RetryBehavior.NON_RETRYABLE, handlerFailure.getRetryBehavior());
      Assert.assertNotNull(failure.getCause());
      failure = (Exception) failure.getCause();
    }

    Assert.assertTrue(failure.getMessage().contains("intentional failure"));

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_INTERNAL")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test(timeout = 20000)
  public void failHandlerSleepMetrics() throws InterruptedException {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("sleep"));

    Map<String, String> execFailedTags =
        getOperationTags().put(MetricsTag.TASK_FAILURE_TYPE, "timeout").buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  @Test
  public void failHandlerErrorMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("error"));
    Assert.assertTrue(invocationCount.get("error") > 1);

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_INTERNAL")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(
              MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, c -> c >= 1);
        });
  }

  @Test
  public void handlerErrorNonRetryableMetrics() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException workflowException =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute("handlererror-nonretryable"));
    assertNoRetries("handlererror-nonretryable");
    HandlerException handlerFailure =
        assertNexusOperationFailure(HandlerException.class, workflowException);
    Assert.assertTrue(handlerFailure.getMessage().contains("intentional failure"));
    Assert.assertEquals(HandlerException.ErrorType.INTERNAL, handlerFailure.getErrorType());
    Assert.assertEquals(
        HandlerException.RetryBehavior.NON_RETRYABLE, handlerFailure.getRetryBehavior());

    Map<String, String> execFailedTags =
        getOperationTags()
            .put(MetricsTag.TASK_FAILURE_TYPE, "handler_error_INTERNAL")
            .buildKeepingLast();
    Eventually.assertEventually(
        Duration.ofSeconds(3),
        () -> {
          reporter.assertTimer(
              MetricsType.NEXUS_SCHEDULE_TO_START_LATENCY, getBaseTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_EXEC_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertTimer(
              MetricsType.NEXUS_TASK_E2E_LATENCY, getOperationTags().buildKeepingLast());
          reporter.assertCounter(MetricsType.NEXUS_EXEC_FAILED_COUNTER, execFailedTags, 1);
        });
  }

  private void assertNoRetries(String testCase) {
    Assert.assertEquals(new Integer(1), invocationCount.get(testCase));
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String operation) {
      TestNexusServices.TestNexusService1 testNexusService =
          Workflow.newNexusServiceStub(
              TestNexusServices.TestNexusService1.class,
              NexusServiceOptions.newBuilder()
                  .setOperationOptions(
                      NexusOperationOptions.newBuilder()
                          .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                          .build())
                  .build());
      return testNexusService.operation(operation);
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync(
          (ctx, details, operation) -> {
            invocationCount.put(operation, invocationCount.getOrDefault(operation, 0) + 1);
            if (invocationCount.get(operation) > 1) {
              throw OperationException.failure(new RuntimeException("exceeded invocation count"));
            }
            switch (operation) {
              case "success":
                return operation;
              case "fail":
                throw OperationException.failure(new RuntimeException("intentional failure"));
              case "fail-app":
                throw OperationException.failure(
                    ApplicationFailure.newFailure("intentional failure", "TestFailure", "foo"));
              case "fail-msg-app":
                throw OperationException.failure(
                    "failure message",
                    ApplicationFailure.newFailure("intentional failure", "TestFailure", "foo"));
              case "cancel":
                throw OperationException.canceled(new RuntimeException("intentional cancel"));
              case "cancel-app":
                throw OperationException.canceled(
                    ApplicationFailure.newFailure("intentional cancel", "TestFailure", "foo"));
              case "cancel-msg-app":
                throw OperationException.canceled(
                    "failure message",
                    ApplicationFailure.newFailure("intentional cancel", "TestFailure", "foo"));
              case "handlererror":
                throw new HandlerException(
                    HandlerException.ErrorType.BAD_REQUEST, new RuntimeException("handlererror"));
              case "handlererror-app":
                throw new HandlerException(
                    HandlerException.ErrorType.BAD_REQUEST,
                    ApplicationFailure.newFailure("intentional failure", "TestFailure", "foo"));
              case "handlererror-msg-app":
                throw new HandlerException(
                    HandlerException.ErrorType.BAD_REQUEST,
                    "handler failure message",
                    ApplicationFailure.newFailure("intentional failure", "TestFailure", "foo"));
              case "handlererror-nonretryable":
                throw new HandlerException(
                    HandlerException.ErrorType.INTERNAL,
                    ApplicationFailure.newNonRetryableFailure("intentional failure", "TestFailure"),
                    HandlerException.RetryBehavior.NON_RETRYABLE);
              case "handlererror-no-cause":
                throw new HandlerException(
                    HandlerException.ErrorType.BAD_REQUEST,
                    "handler failure message",
                    (Throwable) null);
              case "already-started":
                throw new WorkflowExecutionAlreadyStarted(
                    WorkflowExecution.getDefaultInstance(), "TestWorkflowType", null);
              case "retryable-application-failure":
                throw ApplicationFailure.newFailure("intentional failure", "TestFailure");
              case "non-retryable-application-failure":
                throw ApplicationFailure.newNonRetryableFailure(
                    "intentional failure", "TestFailure", "foo");
              case "sleep":
                try {
                  Thread.sleep(11000);
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
                return operation;
              case "error":
                throw new Error("error");
              case "canceled":
                throw OperationException.canceled(new RuntimeException("canceled"));
              default:
                // Should never happen
                Assert.fail();
            }
            return operation;
          });
    }
  }
}
