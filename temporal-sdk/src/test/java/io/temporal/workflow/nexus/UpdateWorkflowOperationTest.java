package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.internal.common.env.EnvironmentVariableUtils;
import io.temporal.internal.nexus.OperationToken;
import io.temporal.internal.nexus.OperationTokenType;
import io.temporal.internal.nexus.OperationTokenUtil;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

public class UpdateWorkflowOperationTest extends BaseNexusTest {

  private static final String asyncVal = "async";
  private static final String doneSignal = "done";
  private static final String targetWorkflowId = "update-handler-workflow-" + UUID.randomUUID();

  @ClassRule
  public static SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CallerWorkflow.class, HandlerWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .setUseTimeskipping(false)
          .build();

  private static WorkflowStub targetStub;

  @BeforeClass
  public static void startTargetWorkflow() {
    // start the handler workflow
    targetStub =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                "HandlerWorkflow",
                WorkflowOptions.newBuilder()
                    .setWorkflowId(targetWorkflowId)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .build());
    targetStub.start();
  }

  @AfterClass
  public static void completeTargetWorkflow() {
    // complete the handler workflow
    targetStub.signal(doneSignal);
  }

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void syncValidationFailureFailsOperation() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflow1.class, "caller-validation-failure");

    // empty value fails setValueValidator -> synchronous operation failure, no token issued
    WorkflowFailedException e =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute(targetWorkflowId + "|"));

    Assert.assertTrue(e.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) e.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
  }

  @Test
  public void syncCompletedUpdateReturnsResult() {
    // NOTE: this test makes no assumptions about whether update callbacks are enabled
    // If enabled, the first call will resolve asynchronously due to NEXUS-489. If not,
    // it resolves sync. In either case, the second call dedups and resolves synchronously
    String fixedUpdateId = "fixed-update-id-" + testWorkflowRule.getTaskQueue();

    TestWorkflows.TestWorkflow1 first =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflow1.class, "caller-dedup-completed-first");
    Assert.assertEquals(
        "immediate-value", first.execute(targetWorkflowId + "|immediate-value|" + fixedUpdateId));

    TestWorkflows.TestWorkflow1 second =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflow1.class, "caller-dedup-completed-second");
    Assert.assertEquals(
        "immediate-value",
        second.execute(targetWorkflowId + "|immediate-value|" + fixedUpdateId + "|dedup"));
  }

  @Test
  public void asyncUpdateWorkflowOperationCompletes() {
    // only run this test if the server supports update completion callbacks
    // currently only for local server manual checks until the in-memory test
    // server can support update completion callbacks
    assumeTrue(
        "server does not support update completion callbacks",
        EnvironmentVariableUtils.readBooleanFlag("UPDATE_CALLBACKS_SUPPORTED"));

    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.TestWorkflow1.class, "caller-async-update");
    String result = workflowStub.execute(targetWorkflowId + "|" + asyncVal);
    Assert.assertEquals(asyncVal, result);
  }

  public static class CallerWorkflow implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String input) {
      String[] parts = input.split("\\|", 4);
      String targetWorkflowId = parts[0];
      String value = parts.length > 1 ? parts[1] : "";
      String updateId = parts.length > 2 ? parts[2] : null;
      boolean expectDedup = parts.length > 3 && "dedup".equals(parts[3]);

      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(30))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();

      TestNexusUpdateService serviceStub =
          Workflow.newNexusServiceStub(TestNexusUpdateService.class, serviceOptions);

      NexusOperationHandle<String> handle =
          Workflow.startNexusOperation(
              serviceStub::update, new UpdateRequest(targetWorkflowId, value, updateId));

      NexusOperationExecution execution = handle.getExecution().get();
      if (asyncVal.equals(value)) {
        // must be issued with a token
        Assert.assertTrue(execution.getOperationToken().isPresent());
        OperationToken token =
            OperationTokenUtil.loadWorkflowUpdateOperationToken(
                execution.getOperationToken().get());
        Assert.assertEquals(OperationTokenType.WORKFLOW_UPDATE, token.getType());
        Assert.assertEquals(targetWorkflowId, token.getWorkflowId());
      } else if (expectDedup) {
        // dedup onto an already-completed update - sync, no token
        Assert.assertFalse(execution.getOperationToken().isPresent());
      }

      return handle.getResult().get();
    }
  }

  // Handler workflow
  @WorkflowInterface
  public interface HandlerWorkflow {
    @WorkflowMethod
    void execute();

    @UpdateMethod(name = "setValue")
    String setValue(String value);

    @UpdateValidatorMethod(updateName = "setValue")
    void setValueValidator(String value);

    @SignalMethod(name = doneSignal)
    void done();
  }

  public static class HandlerWorkflowImpl implements HandlerWorkflow {
    private boolean completed;

    @Override
    public void execute() {
      Workflow.await(() -> completed);
    }

    @Override
    public void done() {
      completed = true;
    }

    @Override
    public String setValue(String value) {
      if (asyncVal.equals(value)) {
        Workflow.sleep(Duration.ofSeconds(1));
      }
      return value;
    }

    @Override
    public void setValueValidator(String value) {
      if (value == null || value.isEmpty()) {
        throw new IllegalArgumentException("value required");
      }
    }
  }

  // Nexus Service
  @Service
  public interface TestNexusUpdateService {
    @Operation
    String update(UpdateRequest input);
  }

  @ServiceImpl(service = TestNexusUpdateService.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<UpdateRequest, String> update() {
      return TemporalOperationHandler.create(
          (context, client, input) -> {
            UpdateOptions.Builder<String> optionsBuilder =
                UpdateOptions.newBuilder(String.class)
                    .setUpdateName("setValue")
                    .setWaitForStage(WorkflowUpdateStage.ACCEPTED);
            if (input.getUpdateId() != null) {
              optionsBuilder.setUpdateId(input.getUpdateId());
            }
            return client.startWorkflowUpdate(
                HandlerWorkflow.class,
                input.getTargetWorkflowId(),
                HandlerWorkflow::setValue,
                input.getValue(),
                optionsBuilder.build());
          });
    }
  }

  // container for the update input
  public static final class UpdateRequest {
    public String targetWorkflowId;
    public String value;
    public String updateId;

    public UpdateRequest() {}

    public UpdateRequest(String targetWorkflowId, String value, String updateId) {
      this.targetWorkflowId = targetWorkflowId;
      this.value = value;
      this.updateId = updateId;
    }

    public String getTargetWorkflowId() {
      return targetWorkflowId;
    }

    public String getValue() {
      return value;
    }

    public String getUpdateId() {
      return updateId;
    }
  }
}
