package io.temporal.opentracing;

import static org.junit.Assert.assertEquals;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class UpdateWithStartTest {

  private static final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  private final OpenTracingOptions OT_OPTIONS =
      OpenTracingOptions.newBuilder().setTracer(mockTracer).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTracingClientInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String run(String input);

    @UpdateMethod
    void update(String update);
  }

  public static class WorkflowImpl implements TestWorkflow {

    private String update;

    @Override
    public String run(String input) {
      return update;
    }

    @Override
    public void update(String update) {
      this.update = update;
    }
  }

  @Test
  public void updateWithStart() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .setWorkflowIdConflictPolicy(
                    WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_TERMINATE_EXISTING)
                .validateBuildWithDefaults());

    Span span = mockTracer.buildSpan("ClientFunction").start();

    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      WorkflowClient.executeUpdateWithStart(
          workflow::update,
          "input",
          UpdateOptions.<Void>newBuilder().build(),
          new WithStartWorkflowOperation<>(workflow::run, "updateInput"));
    } finally {
      span.finish();
    }

    // wait for the workflow completion
    WorkflowStub.fromTyped(workflow).getResult(String.class);

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());

    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");

    MockSpan workflowStartSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), workflowStartSpan.parentId());
    assertEquals("UpdateWithStartWorkflow:TestWorkflow", workflowStartSpan.operationName());

    if (SDKTestWorkflowRule.useExternalService) {
      List<MockSpan> workflowSpans = spansHelper.getByParentSpan(workflowStartSpan);
      assertEquals(2, workflowSpans.size());

      MockSpan workflowUpdateSpan = workflowSpans.get(0);
      assertEquals(workflowStartSpan.context().spanId(), workflowUpdateSpan.parentId());
      assertEquals("HandleUpdate:update", workflowUpdateSpan.operationName());

      MockSpan workflowRunSpan = workflowSpans.get(1);
      assertEquals(workflowStartSpan.context().spanId(), workflowRunSpan.parentId());
      assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.operationName());
    } else {
      List<MockSpan> workflowRunSpans = spansHelper.getByParentSpan(workflowStartSpan);
      assertEquals(1, workflowRunSpans.size());

      MockSpan workflowRunSpan = workflowRunSpans.get(0);
      assertEquals(workflowStartSpan.context().spanId(), workflowRunSpan.parentId());
      assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.operationName());
    }
  }
}
