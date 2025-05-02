package io.temporal.workflow;

import static org.junit.Assert.*;

import com.google.common.base.Throwables;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowServiceException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class GrpcRetryerFunctionalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(FiveSecondsSleepingWorkflow.class).build();

  private static ScheduledExecutorService scheduledExecutor;

  @BeforeClass
  public static void beforeClass() {
    scheduledExecutor = Executors.newScheduledThreadPool(1);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    scheduledExecutor.shutdownNow();
  }

  /**
   * This test verifies that if GRPC Deadline in GRPC Context is reached even before the request, we
   * return fast and with the correct DEADLINE_EXCEEDED error, and we don't try to retry the
   * DEADLINE_EXCEEDED.
   */
  @Test(timeout = 2000)
  public void contextDeadlineExpiredAtStart() {
    NoArgsWorkflow workflow = testWorkflowRule.newWorkflowStub(NoArgsWorkflow.class);

    AtomicReference<Exception> exRef = new AtomicReference<>();
    Context.current()
        .withDeadlineAfter(-1, TimeUnit.MILLISECONDS, scheduledExecutor)
        // create a new deadline that already expired
        .run(
            () -> {
              try {
                workflow.execute();
                fail();
              } catch (Exception e) {
                exRef.set(e);
              }
            });

    WorkflowServiceException exception = (WorkflowServiceException) exRef.get();
    Throwable cause = exception.getCause();

    assertTrue(cause instanceof StatusRuntimeException);
    assertEquals(
        Status.DEADLINE_EXCEEDED.getCode(), ((StatusRuntimeException) cause).getStatus().getCode());
  }

  /**
   * This test verifies that if GRPC Deadline reached in the middle of workflow execution, we return
   * fast and with the correct DEADLINE_EXCEEDED error, and we don't try to retry the
   * DEADLINE_EXCEEDED.
   */
  @Test(timeout = 2500)
  public void contextDeadlineExpiredBeforeWorkflowFinish() {
    NoArgsWorkflow workflow = testWorkflowRule.newWorkflowStub(NoArgsWorkflow.class);

    AtomicReference<Exception> exRef = new AtomicReference<>();
    Context.current()
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS, scheduledExecutor)
        // create a deadline that expires before workflow finishes
        .run(
            () -> {
              try {
                workflow.execute();
                fail();
              } catch (Exception e) {
                exRef.set(e);
              }
            });

    Exception exception = exRef.get();
    assertTrue(
        "Expected WorkflowServiceException, got "
            + exception
            + " \n "
            + Throwables.getStackTraceAsString(exception),
        exception instanceof WorkflowServiceException);
    Throwable cause = exception.getCause();

    assertTrue(cause instanceof StatusRuntimeException);
    assertEquals(
        Status.DEADLINE_EXCEEDED.getCode(), ((StatusRuntimeException) cause).getStatus().getCode());
  }

  public static class FiveSecondsSleepingWorkflow implements NoArgsWorkflow {

    @Override
    public void execute() {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
