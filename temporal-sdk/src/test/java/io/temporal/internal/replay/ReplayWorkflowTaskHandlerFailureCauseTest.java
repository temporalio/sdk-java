package io.temporal.internal.replay;

import static org.junit.Assert.assertEquals;

import com.google.protobuf.ByteString;
import io.temporal.api.enums.v1.WorkflowTaskFailedCause;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskFailedRequest;
import io.temporal.worker.NonDeterministicException;
import java.lang.reflect.Method;
import org.junit.Test;

/**
 * Unit tests for ReplayWorkflowTaskHandler failure cause assignment. Tests the failureToWFTResult
 * method to ensure correct WorkflowTaskFailedCause is set.
 */
public class ReplayWorkflowTaskHandlerFailureCauseTest {

  @Test
  public void testUnhandledExceptionSetsCorrectFailureCause() throws Exception {
    // Create a simple workflow task with minimal required fields
    PollWorkflowTaskQueueResponse workflowTask =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFromUtf8("test-token"))
            .build();

    // Test with RuntimeException (unhandled failure)
    RuntimeException unhandledException = new RuntimeException("Simulated unhandled failure");
    RespondWorkflowTaskFailedRequest result =
        testFailureToWFTResult(workflowTask, unhandledException);

    assertEquals(
        "Should set WORKFLOW_WORKER_UNHANDLED_FAILURE cause",
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_WORKFLOW_WORKER_UNHANDLED_FAILURE,
        result.getCause());
  }

  @Test
  public void testNonDeterministicExceptionSetsCorrectFailureCause() throws Exception {
    // Create a simple workflow task with minimal required fields
    PollWorkflowTaskQueueResponse workflowTask =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFromUtf8("test-token"))
            .build();

    // Test with NonDeterministicException
    NonDeterministicException nonDetException =
        new NonDeterministicException("Simulated non-deterministic failure");
    RespondWorkflowTaskFailedRequest result = testFailureToWFTResult(workflowTask, nonDetException);

    assertEquals(
        "Should set NON_DETERMINISTIC_ERROR cause",
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_NON_DETERMINISTIC_ERROR,
        result.getCause());
  }

  @Test
  public void testErrorExceptionSetsCorrectFailureCause() throws Exception {
    // Create a simple workflow task with minimal required fields
    PollWorkflowTaskQueueResponse workflowTask =
        PollWorkflowTaskQueueResponse.newBuilder()
            .setTaskToken(ByteString.copyFromUtf8("test-token"))
            .build();

    // Test with Error (unhandled failure)
    Error error = new Error("Simulated error");
    RespondWorkflowTaskFailedRequest result = testFailureToWFTResult(workflowTask, error);

    assertEquals(
        "Should set WORKFLOW_WORKER_UNHANDLED_FAILURE cause for Error",
        WorkflowTaskFailedCause.WORKFLOW_TASK_FAILED_CAUSE_WORKFLOW_WORKER_UNHANDLED_FAILURE,
        result.getCause());
  }

  /** Helper method to test the private failureToWFTResult method using reflection. */
  private RespondWorkflowTaskFailedRequest testFailureToWFTResult(
      PollWorkflowTaskQueueResponse workflowTask, Throwable failure) throws Exception {

    // Create a minimal handler instance just for testing the failureToWFTResult method
    // We need to provide non-null values for required parameters
    com.uber.m3.tally.NoopScope metricsScope = new com.uber.m3.tally.NoopScope();
    io.temporal.internal.worker.SingleWorkerOptions options =
        io.temporal.internal.worker.SingleWorkerOptions.newBuilder()
            .setDataConverter(
                io.temporal.common.converter.DefaultDataConverter.newDefaultInstance())
            .setIdentity("test-worker")
            .setMetricsScope(metricsScope)
            .build();

    io.temporal.internal.worker.WorkflowExecutorCache cache =
        new io.temporal.internal.worker.WorkflowExecutorCache(
            10, new io.temporal.internal.worker.WorkflowRunLockManager(), metricsScope);

    // Mock the required service parameter
    io.temporal.serviceclient.WorkflowServiceStubs mockService =
        org.mockito.Mockito.mock(io.temporal.serviceclient.WorkflowServiceStubs.class);

    ReplayWorkflowTaskHandler handler =
        new ReplayWorkflowTaskHandler(
            "test-namespace",
            null, // workflowFactory - can be null for this test
            cache,
            options,
            null, // stickyTaskQueue - can be null
            null, // stickyTaskQueueScheduleToStartTimeout - can be null
            mockService, // service - must be non-null
            null // localActivityDispatcher - can be null
            );

    // Use reflection to access the private failureToWFTResult method
    Method method =
        ReplayWorkflowTaskHandler.class.getDeclaredMethod(
            "failureToWFTResult",
            io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponseOrBuilder.class,
            Throwable.class,
            io.temporal.common.converter.DataConverter.class);
    method.setAccessible(true);
    io.temporal.internal.worker.WorkflowTaskHandler.Result result =
        (io.temporal.internal.worker.WorkflowTaskHandler.Result)
            method.invoke(
                handler,
                workflowTask,
                failure,
                io.temporal.common.converter.DefaultDataConverter.newDefaultInstance());

    // Extract the RespondWorkflowTaskFailedRequest from the Result
    return result.getTaskFailed();
  }
}
