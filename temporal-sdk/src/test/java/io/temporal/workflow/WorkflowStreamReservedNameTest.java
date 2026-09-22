package io.temporal.workflow;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowQueryException;
import io.temporal.client.WorkflowStub;
import io.temporal.internal.common.InternalUtils;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that the {@code __temporal_workflow_stream_} sub-namespace is permitted for signal,
 * update, and query handlers (used by the workflow streams contrib module) while other {@code
 * __temporal_} names remain reserved.
 */
public class WorkflowStreamReservedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestStreamReservedNameWorkflowImpl.class)
          .build();

  @Test
  public void testIsWorkflowStreamReservedName() {
    Assert.assertTrue(
        InternalUtils.isWorkflowStreamReservedName("__temporal_workflow_stream_publish"));
    Assert.assertTrue(
        InternalUtils.isWorkflowStreamReservedName("__temporal_workflow_stream_poll"));
    Assert.assertTrue(
        InternalUtils.isWorkflowStreamReservedName("__temporal_workflow_stream_offset"));
    Assert.assertFalse(InternalUtils.isWorkflowStreamReservedName("__temporal_"));
    Assert.assertFalse(InternalUtils.isWorkflowStreamReservedName("__temporal_foo"));
    Assert.assertFalse(InternalUtils.isWorkflowStreamReservedName("__internal"));
    Assert.assertFalse(InternalUtils.isWorkflowStreamReservedName("events"));
  }

  @Test
  public void testWorkflowStreamReservedNamesAreHandled() {
    TestStreamReservedNameWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestStreamReservedNameWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    WorkflowStub stub =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(execution.getWorkflowId());

    stub.signal("__temporal_workflow_stream_publish", "a");
    stub.signal("__temporal_workflow_stream_publish", "b");
    String updateResult = stub.update("__temporal_workflow_stream_poll", String.class, "c");
    Assert.assertEquals("polled:c", updateResult);
    Long offset = stub.query("__temporal_workflow_stream_offset", Long.class);
    Assert.assertEquals(Long.valueOf(3), offset);

    // Other __temporal_ names remain reserved.
    try {
      stub.query("__temporal_other", Long.class);
      Assert.fail("unreachable");
    } catch (WorkflowQueryException e) {
      Assert.assertTrue(e.getCause().getMessage().contains("Unknown query type"));
    }

    stub.signal("finish");
    stub.getResult(String.class);
  }

  public interface StreamHandlersListener {
    @SignalMethod(name = "__temporal_workflow_stream_publish")
    void publish(String value);

    @UpdateMethod(name = "__temporal_workflow_stream_poll")
    String poll(String value);

    @QueryMethod(name = "__temporal_workflow_stream_offset")
    long offset();
  }

  @WorkflowInterface
  public interface TestStreamReservedNameWorkflow {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void finish();
  }

  public static class TestStreamReservedNameWorkflowImpl implements TestStreamReservedNameWorkflow {
    private final List<String> values = new ArrayList<>();
    private boolean finished;

    @Override
    public String execute() {
      Workflow.registerListener(
          new StreamHandlersListener() {
            @Override
            public void publish(String value) {
              values.add(value);
            }

            @Override
            public String poll(String value) {
              values.add(value);
              return "polled:" + value;
            }

            @Override
            public long offset() {
              return values.size();
            }
          });
      Workflow.await(() -> finished);
      return String.join(",", values);
    }

    @Override
    public void finish() {
      finished = true;
    }
  }
}
