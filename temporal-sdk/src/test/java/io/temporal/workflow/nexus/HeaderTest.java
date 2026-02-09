package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

// Test the start operation handler receives the correct headers
public class HeaderTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testOperationHeaders() {
    TestWorkflow workflowStub = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    Map<String, String> headers = workflowStub.execute(testWorkflowRule.getTaskQueue());
    // Operation-timeout is set because the schedule-to-close timeout is capped by workflow run
    // timeout, which is set by
    // default for tests.
    Assert.assertTrue(headers.containsKey("operation-timeout"));
    Assert.assertTrue(headers.containsKey("request-timeout"));
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    Map<String, String> execute(String arg);
  }

  public static class TestNexus implements TestWorkflow {
    @Override
    public Map<String, String> execute(String input) {
      // Try to call with the typed stub
      TestNexusService serviceStub = Workflow.newNexusServiceStub(TestNexusService.class);
      return serviceStub.operation();
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    Map<String, String> operation();
  }

  @ServiceImpl(service = TestNexusService.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Void, Map<String, String>> operation() {
      return OperationHandler.sync((context, details, input) -> context.getHeaders());
    }
  }
}
