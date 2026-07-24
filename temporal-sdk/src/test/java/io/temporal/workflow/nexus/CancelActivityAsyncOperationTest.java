package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.nexus.CancelActivityExecutionInput;
import io.temporal.nexus.TemporalOperationCancelContext;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class CancelActivityAsyncOperationTest extends BaseNexusTest {

  static final AtomicBoolean cancelled = new AtomicBoolean(false);
  static final AtomicBoolean customCancelInvoked = new AtomicBoolean(false);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(DefaultCancelNexus.class, OverriddenCancelNexus.class)
          .setActivityImplementations(new HeartbeatingActivityImpl())
          .setNexusServiceImplementation(
              new DefaultCancelNexusServiceImpl(), new OverriddenCancelNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Before
  public void resetState() {
    cancelled.set(false);
    customCancelInvoked.set(false);
  }

  @Test(timeout = 60_000)
  public void testDefaultActivityCancel() {
    // Standalone-activity Nexus path requires a real Temporal server; the in-process test server
    // does not implement StartActivityExecution.
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    WorkflowStub stub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("DefaultCancelCaller");
    stub.start(testWorkflowRule.getTaskQueue());
    // Either succeeds with "ok" or throws a workflow failure; we only care about the cancel path.
    try {
      stub.getResult(String.class);
    } catch (WorkflowFailedException ignored) {
      // Acceptable: caller workflow may surface the cancel as failure.
    }
    // History contains EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED on the caller workflow.
    testWorkflowRule.assertHistoryEvent(
        stub.getExecution().getWorkflowId(),
        io.temporal.api.enums.v1.EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED);
    // Activity worker observed cancel.
    Assert.assertTrue("activity should have observed cancel", cancelled.get());
  }

  @Test(timeout = 60_000)
  public void testOverriddenActivityCancel() {
    // Same constraint as testDefaultActivityCancel.
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    WorkflowStub stub =
        testWorkflowRule.newUntypedWorkflowStubTimeoutOptions("OverriddenCancelCaller");
    stub.start(testWorkflowRule.getTaskQueue());
    try {
      stub.getResult(String.class);
    } catch (WorkflowFailedException ignored) {
      // Acceptable.
    }
    // The overriding handler should have been called.
    Assert.assertTrue("override should have been invoked", customCancelInvoked.get());
    // Default cancel was not invoked: activity ran until startToCloseTimeout fired
    // (no ActivityCompletionException raised on the worker).
    Assert.assertFalse("default cancel should not have been invoked", cancelled.get());
  }

  @ActivityInterface
  public interface HeartbeatingActivity {
    @ActivityMethod
    String process(String input);
  }

  public static class HeartbeatingActivityImpl implements HeartbeatingActivity {
    @Override
    public String process(String input) {
      while (true) {
        try {
          Activity.getExecutionContext().heartbeat(null);
        } catch (ActivityCompletionException ex) {
          cancelled.set(true);
          throw ex;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      return "done";
    }
  }

  @WorkflowInterface
  public interface DefaultCancelCaller {
    @WorkflowMethod
    String execute(String taskQueue);
  }

  @WorkflowInterface
  public interface OverriddenCancelCaller {
    @WorkflowMethod
    String execute(String taskQueue);
  }

  @io.nexusrpc.Service
  public interface DefaultCancelNexusService {
    @io.nexusrpc.Operation
    String operation(String taskQueue);
  }

  @io.nexusrpc.Service
  public interface OverrideCancelNexusService {
    @io.nexusrpc.Operation
    String operation(String taskQueue);
  }

  // ---- Default cancel ----

  public static class DefaultCancelNexus implements DefaultCancelCaller {
    @Override
    public String execute(String taskQueue) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(15))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      DefaultCancelNexusService stub =
          Workflow.newNexusServiceStub(DefaultCancelNexusService.class, serviceOptions);
      try {
        Workflow.newCancellationScope(
                () -> {
                  NexusOperationHandle<String> handle =
                      Workflow.startNexusOperation(stub::operation, taskQueue);
                  handle.getExecution().get();
                  CancellationScope.current().cancel();
                  handle.getResult().get();
                })
            .run();
      } catch (NexusOperationFailure failure) {
        if (!(failure.getCause() instanceof CanceledFailure)) {
          throw failure;
        }
      }
      return "ok";
    }
  }

  @ServiceImpl(service = DefaultCancelNexusService.class)
  public class DefaultCancelNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return TemporalOperationHandler.create(
          (context, client, input) ->
              client.startActivity(
                  HeartbeatingActivity.class,
                  HeartbeatingActivity::process,
                  input,
                  StartActivityOptions.newBuilder()
                      .setId("act-" + context.getRequestId())
                      .setTaskQueue(input)
                      .setStartToCloseTimeout(Duration.ofSeconds(60))
                      .setHeartbeatTimeout(Duration.ofSeconds(2))
                      .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                      .build()));
    }
  }

  // ---- Overridden cancel ----

  public static class OverriddenCancelNexus implements OverriddenCancelCaller {
    @Override
    public String execute(String taskQueue) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(15))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      OverrideCancelNexusService stub =
          Workflow.newNexusServiceStub(OverrideCancelNexusService.class, serviceOptions);
      try {
        Workflow.newCancellationScope(
                () -> {
                  NexusOperationHandle<String> handle =
                      Workflow.startNexusOperation(stub::operation, taskQueue);
                  handle.getExecution().get();
                  CancellationScope.current().cancel();
                  handle.getResult().get();
                })
            .run();
      } catch (NexusOperationFailure failure) {
        if (!(failure.getCause() instanceof CanceledFailure)) {
          throw failure;
        }
      }
      return "ok";
    }
  }

  @ServiceImpl(service = OverrideCancelNexusService.class)
  public class OverriddenCancelNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return new TemporalOperationHandler<String, String>(
          (context, client, input) ->
              client.startActivity(
                  HeartbeatingActivity.class,
                  HeartbeatingActivity::process,
                  input,
                  StartActivityOptions.newBuilder()
                      .setId("act-override-" + context.getRequestId())
                      .setTaskQueue(input)
                      .setStartToCloseTimeout(Duration.ofSeconds(5))
                      .setHeartbeatTimeout(Duration.ofSeconds(2))
                      .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                      .build())) {
        @Override
        protected void cancelActivityExecution(
            TemporalOperationCancelContext ctx, CancelActivityExecutionInput input) {
          customCancelInvoked.set(true);
          // Intentionally do NOT invoke default cancel; the activity will self-terminate via
          // startToCloseTimeout.
        }
      };
    }
  }
}
