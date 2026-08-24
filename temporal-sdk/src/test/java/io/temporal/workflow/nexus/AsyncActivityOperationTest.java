package io.temporal.workflow.nexus;

import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.StartActivityOptions;
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

public class AsyncActivityOperationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setActivityImplementations(new TestActivityImpl())
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testActivityOperationEndToEnd() {
    // The in-process test-server does not implement the StartActivityExecution RPC; the
    // standalone-activity Nexus path requires a real server. Unit-only token assertions stay
    // active in OperationTokenTest.
    assumeTrue(SDKTestWorkflowRule.useExternalService);
    // The caller workflow receives the activity result and an ACTIVITY_EXECUTION operation token.
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute("world");
    Assert.assertEquals("hello world", result);
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
          NexusServiceOptions.newBuilder().setOperationOptions(options).build();
      TestNexusServices.TestNexusService1 serviceStub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);

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
}
