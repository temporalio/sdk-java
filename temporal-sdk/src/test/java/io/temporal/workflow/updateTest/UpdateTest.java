package io.temporal.workflow.updateTest;

import static io.temporal.client.WorkflowUpdateStage.ACCEPTED;
import static io.temporal.client.WorkflowUpdateStage.COMPLETED;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import io.temporal.activity.*;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.ResetReapplyExcludeType;
import io.temporal.api.enums.v1.ResetReapplyType;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse;
import io.temporal.client.*;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.client.CompletedWorkflowUpdateHandleImpl;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.TestWorkflows.WorkflowWithUpdate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Ignore
public class UpdateTest {

  private static final Logger log = LoggerFactory.getLogger(UpdateTest.class);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().build())
          .setWorkflowTypes(TestUpdateWorkflowImpl.class, TestWaitingUpdate.class)
          .setActivityImplementations(new ActivityImpl())
          .build();

  @Test
  public void testUpdate() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithUpdate workflow = workflowClient.newWorkflowStub(WorkflowWithUpdate.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());

    assertEquals(workflowId, execution.getWorkflowId());

    assertEquals("Execute-Hello Update", workflow.update(0, "Hello Update"));
    assertThrows(WorkflowUpdateException.class, () -> workflow.update(-2, "Bad update"));

    testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
    testWorkflowRule.invalidateWorkflowCache();

    // send an update that will fail in the update handler
    assertThrows(WorkflowUpdateException.class, () -> workflow.complete());
    assertThrows(WorkflowUpdateException.class, () -> workflow.update(1, ""));

    assertEquals("Execute-Hello Update 2", workflow.update(0, "Hello Update 2"));
    assertThrows(WorkflowUpdateException.class, () -> workflow.update(0, "Bad update"));

    workflow.complete();

    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("Execute-Hello Update Execute-Hello Update 2", result);
  }

  private static class FakesResultUpdateInterceptor extends WorkflowClientInterceptorBase {
    @Override
    public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
        WorkflowClientCallsInterceptor next) {
      return new WorkflowClientCallsInterceptorBase(next) {
        @Override
        public <R> WorkflowUpdateHandle<R> startUpdate(StartUpdateInput<R> input) {
          super.startUpdate(input);
          return new CompletedWorkflowUpdateHandleImpl<>(
              "someid", input.getWorkflowExecution(), (R) "fake");
        }
      };
    }
  }

  @Test
  public void testUpdateIntercepted() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient =
        WorkflowClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            WorkflowClientOptions.newBuilder(testWorkflowRule.getWorkflowClient().getOptions())
                .setInterceptors(new FakesResultUpdateInterceptor())
                .validateAndBuildWithDefaults());
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithUpdate workflow = workflowClient.newWorkflowStub(WorkflowWithUpdate.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());
    assertEquals(workflowId, execution.getWorkflowId());

    assertEquals("fake", workflow.update(0, "Hello Update"));
    assertEquals("fake", workflow.update(1, "Hello Update 2"));
    workflow.complete();

    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("Execute-Hello Update Execute-Hello Update 2", result);
  }

  @Test
  public void testUpdateUntyped() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.WorkflowWithUpdate.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    workflowStub.start();

    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    assertEquals("initial", workflowStub.query("getState", String.class));

    // send an update through the sync path
    assertEquals("Execute-Hello", workflowStub.update("update", String.class, 0, "Hello"));
    // send an update through the async path
    WorkflowUpdateHandle<String> updateRef =
        workflowStub.startUpdate("update", ACCEPTED, String.class, 0, "World");
    assertEquals("Execute-World", updateRef.getResultAsync().get());
    // send a bad update that will be rejected through the sync path
    assertThrows(
        WorkflowUpdateException.class,
        () -> workflowStub.update("update", String.class, 0, "Bad Update"));

    // send an update request to a bad name
    assertThrows(
        WorkflowUpdateException.class,
        () -> workflowStub.update("bad_update_name", String.class, 0, "Bad Update"));

    // send an update request to a bad name through the async path
    assertThrows(
        WorkflowUpdateException.class,
        () ->
            workflowStub
                .startUpdate("bad_update_name", WorkflowUpdateStage.COMPLETED, String.class, 0, "")
                .getResult());

    // send a bad update that will be rejected through the sync path
    assertThrows(
        WorkflowUpdateException.class,
        () ->
            workflowStub
                .startUpdate("update", ACCEPTED, String.class, 0, "Bad Update")
                .getResult());

    workflowStub.update("complete", void.class);

    assertEquals("Execute-Hello Execute-World", workflowStub.getResult(String.class));
  }

  @Test
  public void testAsyncTypedUpdate() throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.WorkflowWithUpdate.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));
    workflowStub.start();

    assertEquals("initial", workflowStub.query("getState", String.class));

    TestWorkflows.WorkflowWithUpdate workflow =
        workflowClient.newWorkflowStub(
            TestWorkflows.WorkflowWithUpdate.class, workflowStub.getExecution().getWorkflowId());

    WorkflowUpdateHandle<String> handle =
        WorkflowClient.startUpdate(
            workflow::update,
            0,
            "Hello",
            UpdateOptions.<String>newBuilder().setWaitForStage(COMPLETED).build());
    assertEquals("Execute-Hello", handle.getResultAsync().get());

    assertEquals(
        "Execute-World",
        WorkflowClient.startUpdate(
                workflow::update,
                0,
                "World",
                UpdateOptions.<String>newBuilder().setWaitForStage(ACCEPTED).build())
            .getResultAsync()
            .get());

    assertEquals(
        "Execute-Hello",
        WorkflowClient.startUpdate(
                workflow::update,
                0,
                "World",
                UpdateOptions.<String>newBuilder()
                    .setWaitForStage(COMPLETED)
                    .setUpdateId(handle.getId())
                    .build())
            .getResultAsync()
            .get());

    assertEquals(
        null,
        WorkflowClient.startUpdate(
                workflow::complete,
                UpdateOptions.<Void>newBuilder().setWaitForStage(COMPLETED).build())
            .getResultAsync()
            .get());

    assertEquals("Execute-Hello Execute-World", workflowStub.getResult(String.class));
  }

  @Test
  public void testUpdateResets() {
    assumeTrue(
        "Test Server doesn't support reset workflow", SDKTestWorkflowRule.useExternalService);
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithUpdate workflow = workflowClient.newWorkflowStub(WorkflowWithUpdate.class, options);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    SDKTestWorkflowRule.waitForOKQuery(workflow);
    assertEquals("initial", workflow.getState());

    assertEquals(workflowId, execution.getWorkflowId());

    assertEquals("Execute-Hello Update", workflow.update(0, "Hello Update"));

    // Reset the workflow
    ResetWorkflowExecutionResponse resetResponse =
        workflowClient
            .getWorkflowServiceStubs()
            .blockingStub()
            .resetWorkflowExecution(
                ResetWorkflowExecutionRequest.newBuilder()
                    .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                    .setReason("Integration test")
                    .setWorkflowExecution(execution)
                    .setWorkflowTaskFinishEventId(4)
                    .setRequestId(UUID.randomUUID().toString())
                    .setResetReapplyType(ResetReapplyType.RESET_REAPPLY_TYPE_ALL_ELIGIBLE)
                    .addAllResetReapplyExcludeTypes(
                        Collections.singletonList(
                            ResetReapplyExcludeType.RESET_REAPPLY_EXCLUDE_TYPE_SIGNAL))
                    .build());
    // Create a new workflow stub with the new run ID
    workflow =
        workflowClient.newWorkflowStub(
            WorkflowWithUpdate.class, workflowId, Optional.of(resetResponse.getRunId()));
    assertEquals("Execute-Hello Update 2", workflow.update(0, "Hello Update 2"));
    // Complete would throw an exception if the update was not applied to the reset workflow.
    workflow.complete();
    assertEquals("Execute-Hello Update Execute-Hello Update 2", workflow.execute());
  }

  @Test
  public void testUpdateHandleNotReturnedUntilCompleteWhenAsked()
      throws ExecutionException, InterruptedException {
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    String workflowType = TestWorkflows.WorkflowWithUpdateAndSignal.class.getSimpleName();
    WorkflowStub workflowStub =
        workflowClient.newUntypedWorkflowStub(
            workflowType,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()));

    workflowStub.start();

    SDKTestWorkflowRule.waitForOKQuery(workflowStub);
    assertEquals("initial", workflowStub.query("getState", String.class));

    // Start the update but verify it does not return the handle until the update is complete
    AtomicBoolean updateCompletedLast = new AtomicBoolean(false);
    Future<?> asyncUpdate =
        Executors.newSingleThreadExecutor()
            .submit(
                () -> {
                  WorkflowUpdateHandle<String> handle =
                      workflowStub.startUpdate(
                          UpdateOptions.newBuilder(String.class)
                              .setUpdateName("update")
                              .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                              .build(),
                          "Enchi");
                  updateCompletedLast.set(true);
                  try {
                    assertEquals("Enchi", handle.getResultAsync().get());
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });

    workflowStub.signal("signal", "whatever");
    updateCompletedLast.set(false);

    asyncUpdate.get();
    assertTrue(updateCompletedLast.get());
    workflowStub.update("complete", void.class);
    workflowStub.getResult(List.class);
  }

  public static class TestUpdateWorkflowImpl implements WorkflowWithUpdate {
    String state = "initial";
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> promise = Workflow.newPromise();
    private final TestActivities.TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

    @Override
    public String execute() {
      promise.get();
      return updates.get(0) + " " + updates.get(1);
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public String update(Integer index, String value) {
      if (value.isEmpty()) {
        // test returning an exception from the update handler
        throw ApplicationFailure.newFailure("Value cannot be an empty string", "NonRetryable");
      }
      String result = activity.execute(value);
      updates.add(result);
      return result;
    }

    @Override
    public void updateValidator(Integer index, String value) {
      if (index < 0) {
        throw new IllegalArgumentException("Validation failed");
      }
      if (updates.size() >= 2) {
        throw new IllegalStateException("Received more then 2 update requests");
      }
    }

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {
      if (updates.size() < 2) {
        throw new RuntimeException("Workflow not ready to complete");
      }
    }
  }

  @ActivityInterface
  public interface GreetingActivities {
    @ActivityMethod
    String hello(String input);
  }

  public static class ActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String input) {
      return Activity.getExecutionContext().getInfo().getActivityType() + "-" + input;
    }
  }

  public static class TestWaitingUpdate implements TestWorkflows.WorkflowWithUpdateAndSignal {
    String state = "initial";
    List<String> updates = new ArrayList<>();
    CompletablePromise<Void> signalled = Workflow.newPromise();
    CompletablePromise<Void> promise = Workflow.newPromise();
    private final TestActivities.TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofHours(1)).build());

    @Override
    public List<String> execute() {
      promise.get();
      return updates;
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public void signal(String value) {
      signalled.complete(null);
    }

    @Override
    public String update(String value) {
      Workflow.await(() -> signalled.isCompleted());
      return value;
    }

    @Override
    public void validator(String value) {}

    @Override
    public void complete() {
      promise.complete(null);
    }
  }
}
