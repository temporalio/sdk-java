package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that log records emit during live execution and that replaying a workflow run does not
 * emit duplicate records.
 */
public class LoggerTest extends OtelTestBase {
  private static final AttributeKey<String> ATTR = AttributeKey.stringKey("attr");
  private static final String LOGGER_NAME = "custom-logger";
  private static final String RUN_BODY = "custom workflow run";
  private static final String QUERY_BODY = "custom workflow query";

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void run();

    @QueryMethod
    String queryAndEmit();

    @SignalMethod
    void proceed();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    private boolean proceed;

    @Override
    public void run() {
      emit(RUN_BODY);
      Workflow.await(() -> proceed);
    }

    @Override
    public String queryAndEmit() {
      emit(QUERY_BODY);
      return "ok";
    }

    @Override
    public void proceed() {
      proceed = true;
    }

    private static void emit(String body) {
      GlobalOpenTelemetry.get()
          .getLogsBridge()
          .get(LOGGER_NAME)
          .logRecordBuilder()
          .setBody(body)
          .emit();
    }
  }

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      newRuleBuilder(false).setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void recordsEmitOutsideWorkflows() {
    GlobalOpenTelemetry.get()
        .getLogsBridge()
        .get("outside-workflow")
        .logRecordBuilder()
        .setSeverity(Severity.WARN)
        .setBody("outside")
        .setAllAttributes(Attributes.of(ATTR, "val"))
        .setEventName("outside.event")
        .emit();

    List<LogRecordData> logs = emittedLogs();
    assertEquals(1, logs.size());
    LogRecordData log = logs.get(0);
    assertEquals(Severity.WARN, log.getSeverity());
    assertEquals("outside", log.getBodyValue().asString());
    assertEquals("val", log.getAttributes().get(ATTR));
    assertEquals("outside.event", log.getEventName());
  }

  /**
   * The live run and the live query handler each emit once; replaying the finished run drops its
   * record.
   */
  @Test
  public void liveExecutionEmitsAndReplayDoesNotDuplicate() throws Exception {
    TestWorkflow workflow = testWorkflowRule.newWorkflowStub(TestWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run);

    assertEquals("ok", workflow.queryAndEmit());
    workflow.proceed();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    // Replay the full workflow history and verify the run's record is suppressed on replay.
    WorkflowReplayer.replayWorkflowExecution(
        testWorkflowRule.getExecutionHistory(execution.getWorkflowId()), TestWorkflowImpl.class);

    List<String> bodies = emittedBodies();
    assertEquals(bodies.toString(), 2, bodies.size());
    assertTrue(bodies.toString(), bodies.contains(RUN_BODY));
    assertTrue(bodies.toString(), bodies.contains(QUERY_BODY));
  }

  private static List<String> emittedBodies() {
    List<String> bodies = new ArrayList<>();
    for (LogRecordData log : emittedLogs()) {
      bodies.add(log.getBodyValue().asString());
    }
    return bodies;
  }
}
