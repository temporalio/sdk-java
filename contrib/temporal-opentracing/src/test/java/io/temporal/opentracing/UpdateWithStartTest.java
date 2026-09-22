package io.temporal.opentracing;

import static org.junit.Assert.*;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
    String workflow(String input);

    @UpdateMethod
    String update(String value);
  }

  public static class WorkflowImpl implements TestWorkflow {

    private final CompletablePromise<Void> promise = Workflow.newPromise();
    private String value;

    @Override
    public String workflow(String input) {
      promise.get();
      return value;
    }

    @Override
    public String update(String value) {
      this.value = value;
      promise.complete(null);
      return value;
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
                    WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .validateBuildWithDefaults());

    Span span = mockTracer.buildSpan("ClientFunction").start();

    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      WithStartWorkflowOperation<String> startOp =
          new WithStartWorkflowOperation<>(workflow::workflow, "input");
      WorkflowClient.executeUpdateWithStart(
          workflow::update,
          "update",
          UpdateOptions.<String>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
          startOp);
    } finally {
      span.finish();
    }

    WorkflowStub.fromTyped(workflow).getResult(String.class);
    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());
    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");
    MockSpan workflowStartSpan = spansHelper.getByParentSpan(clientSpan).get(0);

    assertEquals(clientSpan.context().spanId(), workflowStartSpan.parentId());
    assertEquals("UpdateWithStartWorkflow:TestWorkflow", workflowStartSpan.operationName());

    // updateWithStart propagates the start span context into both the StartWorkflow and
    // UpdateWorkflow operation headers
    List<MockSpan> workflowSpans = spansHelper.getByParentSpan(workflowStartSpan);
    assertEquals(2, workflowSpans.size());
    for (MockSpan workflowSpan : workflowSpans) {
      assertEquals(workflowStartSpan.context().spanId(), workflowSpan.parentId());
    }
    Set<String> operationNames =
        workflowSpans.stream().map(MockSpan::operationName).collect(Collectors.toSet());
    assertEquals(
        new HashSet<>(Arrays.asList("HandleUpdate:update", "RunWorkflow:TestWorkflow")),
        operationNames);
  }
}
