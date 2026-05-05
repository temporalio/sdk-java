package io.temporal.testserver.functional;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testserver.functional.common.TestWorkflows;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ContinueAsNewTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new StripsTqFromCanInterceptor())
                  .build())
          .setWorkflowTypes(TestWorkflow.class, OverridingWorkflow.class)
          .build();

  @Test
  public void repeatedFailure() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .build();

    TestWorkflows.WorkflowTakesBool workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowTakesBool.class, options);
    workflowStub.execute(true);

    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    // Verify the latest history is from being CaN'd
    WorkflowExecutionHistory lastHist =
        testWorkflowRule.getExecutionHistory(execution.getWorkflowId());
    Assert.assertFalse(
        lastHist
            .getEvents()
            .get(0)
            .getWorkflowExecutionStartedEventAttributes()
            .getFirstExecutionRunId()
            .isEmpty());
  }

  private static final SearchAttributeKey<String> CUSTOM_KEYWORD =
      SearchAttributeKey.forKeyword("CustomKeywordField");

  @Test
  public void inheritsSearchAttributesAcrossContinueAsNew() {
    // The workflow continues-as-new without specifying search attributes, so the SDK carries the
    // current run's search attributes into the command and the new run must keep them. This matches
    // the real server, which propagates the command's search attributes to the new run.
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setTypedSearchAttributes(
                SearchAttributes.newBuilder().set(CUSTOM_KEYWORD, "initialSA").build())
            .build();

    TestWorkflows.WorkflowTakesBool workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.WorkflowTakesBool.class, options);
    workflowStub.execute(true);

    WorkflowExecutionStartedEventAttributes started =
        getContinuedRunStartedAttributes(workflowStub);

    Assert.assertTrue(
        "Search attributes should be inherited by the continued run",
        started.hasSearchAttributes());
    Assert.assertEquals(
        "initialSA",
        decodeString(started.getSearchAttributes().getIndexedFieldsOrThrow("CustomKeywordField")));
  }

  @Test
  public void overridesSearchAttributesOnContinueAsNew() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder()
            .setWorkflowTaskTimeout(Duration.ofSeconds(1))
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setTypedSearchAttributes(
                SearchAttributes.newBuilder().set(CUSTOM_KEYWORD, "originalSA").build())
            .build();

    OverridingWorkflowInterface workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(OverridingWorkflowInterface.class, options);
    workflowStub.execute(true);

    WorkflowExecutionStartedEventAttributes started =
        getContinuedRunStartedAttributes(workflowStub);

    Assert.assertEquals(
        "overriddenSA",
        decodeString(started.getSearchAttributes().getIndexedFieldsOrThrow("CustomKeywordField")));
  }

  private WorkflowExecutionStartedEventAttributes getContinuedRunStartedAttributes(
      Object workflowStub) {
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    HistoryEvent firstEvent =
        testWorkflowRule.getExecutionHistory(execution.getWorkflowId()).getEvents().get(0);
    Assert.assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED, firstEvent.getEventType());
    WorkflowExecutionStartedEventAttributes started =
        firstEvent.getWorkflowExecutionStartedEventAttributes();
    Assert.assertFalse(
        "Inspected event must belong to the continued run",
        started.getContinuedExecutionRunId().isEmpty());
    return started;
  }

  private static String decodeString(Payload payload) {
    return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, String.class, String.class);
  }

  public static class TestWorkflow implements TestWorkflows.WorkflowTakesBool {
    @Override
    public void execute(boolean doContinue) {
      if (doContinue) {
        Workflow.continueAsNew(false);
      }
    }
  }

  @WorkflowInterface
  public interface OverridingWorkflowInterface {
    @WorkflowMethod
    void execute(boolean doContinue);
  }

  public static class OverridingWorkflow implements OverridingWorkflowInterface {
    @Override
    public void execute(boolean doContinue) {
      if (doContinue) {
        Workflow.continueAsNew(
            ContinueAsNewOptions.newBuilder()
                .setTypedSearchAttributes(
                    SearchAttributes.newBuilder().set(CUSTOM_KEYWORD, "overriddenSA").build())
                .build(),
            false);
      }
    }
  }

  // Verify that we can strip the TQ name and test server continues onto same TQ
  private static class StripsTqFromCanInterceptor extends WorkerInterceptorBase {
    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
      return new WorkflowInboundCallsInterceptorBase(next) {
        @Override
        public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
          next.init(new StripsTqFromCanCmd(outboundCalls));
        }
      };
    }
  }

  private static class StripsTqFromCanCmd extends WorkflowOutboundCallsInterceptorBase {

    private final WorkflowOutboundCallsInterceptor next;

    public StripsTqFromCanCmd(WorkflowOutboundCallsInterceptor next) {
      super(next);
      this.next = next;
    }

    @Override
    public void continueAsNew(ContinueAsNewInput input) {
      next.continueAsNew(
          new ContinueAsNewInput(
              input.getWorkflowType(),
              ContinueAsNewOptions.newBuilder(input.getOptions()).setTaskQueue("").build(),
              input.getArgs(),
              input.getHeader()));
    }
  }
}
