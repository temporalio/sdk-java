package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.HandlerException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.internal.nexus.OperationToken;
import io.temporal.internal.nexus.OperationTokenType;
import io.temporal.internal.nexus.OperationTokenUtil;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityOperationTest extends BaseNexusTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class, DoubleStartNexus.class)
          .setActivityImplementations(new TestActivityImpl())
          .setNexusServiceImplementation(
              new TestNexusServiceImpl(), new DoubleStartNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void testActivityOperationEndToEnd() {
    // The in-process test-server does not implement the StartActivityExecution RPC; the
    // standalone-activity Nexus path requires a real server. Unit-only token assertions stay
    // active in OperationTokenTest.
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    // Combined (a) caller workflow receives "hello " + input
    //          (b) opToken loads as ACTIVITY_EXECUTION with aid="act-" + requestId
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute("world");
    Assert.assertEquals("hello world", result);
  }

  @Test
  public void testDoubleStartActivityThrows() {
    // The first start RPC requires StartActivityExecution which is not implemented by the
    // in-process test server; gate this on a real server so the guard at the second call can be
    // observed.
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    // (c) Calling startActivity twice in one handler invocation throws
    //     HandlerException(BAD_REQUEST).
    TestDoubleStartWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestDoubleStartWorkflow.class);
    WorkflowFailedException e =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute("anything"));
    Assert.assertTrue(e.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) e.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof HandlerException);
    HandlerException handlerException = (HandlerException) nexusFailure.getCause();
    Assert.assertTrue(handlerException.getCause() instanceof ApplicationFailure);
    ApplicationFailure appFailure = (ApplicationFailure) handlerException.getCause();
    Assert.assertEquals("java.lang.IllegalStateException", appFailure.getType());
    Assert.assertTrue(
        appFailure
            .getOriginalMessage()
            .startsWith("Only one async operation can be started per operation handler"));
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String process(String input);
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String process(String input) {
      return "hello " + input;
    }
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(20))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);

      // Drive a handle so we can inspect the operation token for assertion (b).
      NexusOperationHandle<String> handle =
          Workflow.startNexusOperation(serviceStub::operation, input);
      NexusOperationExecution exec = handle.getExecution().get();
      Assert.assertTrue("Operation token should be present", exec.getOperationToken().isPresent());
      OperationToken token = OperationTokenUtil.loadOperationToken(exec.getOperationToken().get());
      Assert.assertEquals(OperationTokenType.ACTIVITY_EXECUTION, token.getType());
      Assert.assertTrue(
          "activityId should start with 'act-' (got " + token.getActivityId() + ")",
          token.getActivityId() != null && token.getActivityId().startsWith("act-"));

      return handle.getResult().get();
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return TemporalOperationHandler.create(
          (context, client, input) ->
              client.startActivity(
                  TestActivity.class,
                  TestActivity::process,
                  input,
                  StartActivityOptions.newBuilder()
                      .setId("act-" + context.getRequestId())
                      .setTaskQueue(testWorkflowRule.getTaskQueue())
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .build()));
    }
  }

  // ---------- Double-start test scaffolding ----------

  public static class DoubleStartNexus implements TestDoubleStartWorkflow {
    @Override
    public String execute(String input) {
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(options)
              .build();
      DoubleStartNexusService stub =
          Workflow.newNexusServiceStub(DoubleStartNexusService.class, serviceOptions);
      return stub.operation(input);
    }
  }

  @WorkflowInterface
  public interface TestDoubleStartWorkflow {
    @WorkflowMethod
    String execute(String input);
  }

  @io.nexusrpc.Service
  public interface DoubleStartNexusService {
    @io.nexusrpc.Operation
    String operation(String input);
  }

  @ServiceImpl(service = DoubleStartNexusService.class)
  public class DoubleStartNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return TemporalOperationHandler.create(
          (context, client, input) -> {
            // First start should succeed.
            client.startActivity(
                TestActivity.class,
                TestActivity::process,
                input,
                StartActivityOptions.newBuilder()
                    .setId("act-first-" + context.getRequestId())
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .build());
            // Second start must throw HandlerException(BAD_REQUEST).
            return client.startActivity(
                TestActivity.class,
                TestActivity::process,
                input,
                StartActivityOptions.newBuilder()
                    .setId("act-second-" + context.getRequestId())
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .build());
          });
    }
  }
}
