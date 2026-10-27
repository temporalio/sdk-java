package io.temporal.internal.testservice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.ChildWorkflowExecutionCanceledEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionCompletedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionStartedEventAttributes;
import io.temporal.api.history.v1.ChildWorkflowExecutionTimedOutEventAttributes;
import io.temporal.api.history.v1.StartChildWorkflowExecutionFailedEventAttributes;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueRequest;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestWorkflowMutableStateTimeSkippingTest {
  private SelfAdvancingTimerImpl timer;
  private LockHandle testLock;
  private TestWorkflowMutableStateImpl state;
  private TestWorkflowService service;

  @Before
  public void setUp() {
    timer = new SelfAdvancingTimerImpl(1, Clock.systemUTC());
    testLock = timer.lockTimeSkipping("test setup");
    TestWorkflowStore store = new TestWorkflowStoreImpl(timer);
    TestVisibilityStore visibilityStore = new TestVisibilityStoreImpl();
    TestNexusEndpointStore nexusStore = new TestNexusEndpointStoreImpl();
    service = new TestWorkflowService(store, visibilityStore, nexusStore, timer);
    state =
        new TestWorkflowMutableStateImpl(
            StartWorkflowExecutionRequest.newBuilder()
                .setNamespace("test")
                .setWorkflowId("workflow")
                .setRequestId("request")
                .setWorkflowType(WorkflowType.newBuilder().setName("workflow"))
                .setTaskQueue(TaskQueue.newBuilder().setName("queue"))
                .build(),
            "run",
            "run",
            Optional.empty(),
            Duration.ZERO,
            Payloads.getDefaultInstance(),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty(),
            Optional.empty(),
            service,
            store,
            visibilityStore,
            nexusStore,
            timer);
    state.startWorkflow(false, null, null);
  }

  @After
  public void tearDown() {
    service.close();
  }

  @Test
  public void terminateScheduledWorkflowTask() throws InterruptedException {
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    assertTimeSkips();
  }

  @Test
  public void terminateStartedWorkflowTask() throws InterruptedException {
    startTask();
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    StatusRuntimeException failure =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                state.completeWorkflowTask(
                    3, RespondWorkflowTaskCompletedRequest.getDefaultInstance()));
    assertEquals(Status.Code.NOT_FOUND, failure.getStatus().getCode());
    assertTimeSkips();
  }

  @Test
  public void terminateWithoutWorkflowTask() throws InterruptedException {
    finishTask();
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    assertTimeSkips();
  }

  @Test
  public void timeoutStartedWorkflowTask() throws InterruptedException {
    startTask();
    timer.skip(Duration.ofDays(3651));
    assertTimeSkips();
    assertEquals(
        WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT,
        state.getWorkflowExecutionStatus());
  }

  @Test
  public void childStartedAfterParentClosed() throws InterruptedException {
    assertClosedParentReleasesLock(
        parent ->
            parent.childWorkflowStarted(
                ChildWorkflowExecutionStartedEventAttributes.getDefaultInstance()));
  }

  @Test
  public void childStartFailedAfterParentClosed() throws InterruptedException {
    assertClosedParentReleasesLock(
        parent ->
            parent.failStartChildWorkflow(
                "child", StartChildWorkflowExecutionFailedEventAttributes.getDefaultInstance()));
  }

  @Test
  public void childCompletedAfterParentClosed() throws InterruptedException {
    assertClosedParentReleasesLock(
        parent ->
            parent.childWorkflowCompleted(
                "child", ChildWorkflowExecutionCompletedEventAttributes.getDefaultInstance()));
  }

  @Test
  public void childFailedAfterParentClosed() throws InterruptedException {
    assertClosedParentReleasesLock(
        parent ->
            parent.childWorkflowFailed(
                "child", ChildWorkflowExecutionFailedEventAttributes.getDefaultInstance()));
  }

  @Test
  public void childCanceledAfterParentClosed() throws InterruptedException {
    assertClosedParentReleasesLock(
        parent ->
            parent.childWorkflowCanceled(
                "child", ChildWorkflowExecutionCanceledEventAttributes.getDefaultInstance()));
  }

  @Test
  public void childTimedOutAfterParentClosed() throws InterruptedException {
    assertClosedParentReleasesLock(
        parent ->
            parent.childWorkflowTimedOut(
                "child", ChildWorkflowExecutionTimedOutEventAttributes.getDefaultInstance()));
  }

  private void startTask() {
    state.startWorkflowTask(
        PollWorkflowTaskQueueResponse.newBuilder(),
        PollWorkflowTaskQueueRequest.getDefaultInstance());
  }

  private void finishTask() {
    startTask();
    state.completeWorkflowTask(3, RespondWorkflowTaskCompletedRequest.getDefaultInstance());
  }

  private void assertClosedParentReleasesLock(Consumer<TestWorkflowMutableState> notification)
      throws InterruptedException {
    finishTask();
    state.terminateWorkflowExecution(TerminateWorkflowExecutionRequest.getDefaultInstance());
    timer.lockTimeSkipping("child notification");
    StatusRuntimeException failure =
        assertThrows(StatusRuntimeException.class, () -> notification.accept(state));
    assertEquals(Status.Code.NOT_FOUND, failure.getStatus().getCode());
    assertTimeSkips();
  }

  private void assertTimeSkips() throws InterruptedException {
    CountDownLatch fired = new CountDownLatch(1);
    timer.schedule(Duration.ofDays(1), fired::countDown, "probe");
    testLock.unlock();
    assertTrue("Clock must skip after workflow closure", fired.await(5, TimeUnit.SECONDS));
  }
}
