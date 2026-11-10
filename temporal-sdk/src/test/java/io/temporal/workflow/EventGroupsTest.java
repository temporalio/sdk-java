package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for Event Groups.
 *
 * <p>Event Group scopes (`EG-SCOPE`), implicit Event Groups (`EG-IMPLICIT`), and aggregation cases
 * involving scopes are not covered: Java only supports attaching Event Groups directly to commands.
 */
public class EventGroupsTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              LabelIdWorkflowImpl.class,
              LabelPayloadWorkflowImpl.class,
              AggregationWorkflowImpl.class,
              TimerCommandWorkflowImpl.class,
              AwaitWorkflowImpl.class)
          .build();

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 1. Explicit Event Groups Marker Label IDs (`EG-LABEL-ID`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void eventGroupIdsAreUsedVerbatim() {
    List<HistoryEvent> events = labelIdHistory();

    // EG-LABEL-ID-20: IDs are used verbatim
    assertEquals(
        "c-id",
        onlyLabel(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "activity-c"))
            .getLabel()
            .getId());

    // EG-LABEL-ID-21: Different labels + same ID => same group
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "activity-d1"), "d-id");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "activity-d2"), "d-id");

    // EG-LABEL-ID-22: Same label + different IDs => distinct groups
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "activity-notC"), "not-c-id");
  }

  private List<HistoryEvent> labelIdHistory() {
    LabelIdWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(LabelIdWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface LabelIdWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class LabelIdWorkflowImpl implements LabelIdWorkflow {
    @Override
    public void execute() {
      EventGroup c =
          Workflow.newEventGroup("c-id", EventGroupOptions.newBuilder().setLabel("ccc").build());
      
      // d1 and d2 have different labels but the same ID => same group
      EventGroup d1 =
          Workflow.newEventGroup("d-id", EventGroupOptions.newBuilder().setLabel("ddd1").build());
      EventGroup d2 =
          Workflow.newEventGroup("d-id", EventGroupOptions.newBuilder().setLabel("ddd2").build());
      
      // Same label as c, but different ID => distinct group
      EventGroup notC =
          Workflow.newEventGroup(
              "not-c-id", EventGroupOptions.newBuilder().setLabel("ccc").build());
      
      Workflow.sleep(Duration.ofMillis(1), timer(c, "activity-c"));
      Workflow.sleep(Duration.ofMillis(1), timer(d1, "activity-d1"));
      Workflow.sleep(Duration.ofMillis(1), timer(d2, "activity-d2"));
      Workflow.sleep(Duration.ofMillis(1), timer(notC, "activity-notC"));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 2. Explicit Event Groups Marker Label Payload (`EG-LABEL-PAYLOAD`)
  //
  // EG-LABEL-PAYLOAD-20 and 21 are in EventGroupsCodecTest: they need a payload codec.
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void eventGroupLabelsConvertToPayloadsAsJsonStrings() {
    List<HistoryEvent> events = labelPayloadHistory();

    HistoryEvent activityB = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "activity-b");
    Payload payload = onlyLabel(activityB).getLabel().getLabel();

    // EG-LABEL-PAYLOAD-00: Label Payload converts to a json/plain JSON string
    assertEquals("json/plain", payload.getMetadataOrThrow("encoding").toStringUtf8());
    assertEquals(
        "Label B",
        DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, String.class, String.class));

    // EG-LABEL-PAYLOAD-02: An omitted label produces no payload
    assertNull(
        labelText(onlyLabel(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "activity-a"))));
  }

  private List<HistoryEvent> labelPayloadHistory() {
    LabelPayloadWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(LabelPayloadWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface LabelPayloadWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class LabelPayloadWorkflowImpl implements LabelPayloadWorkflow {
    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      EventGroup b =
          Workflow.newEventGroup("bbb", EventGroupOptions.newBuilder().setLabel("Label B").build());

      Workflow.sleep(Duration.ofMillis(1), timer(a, "activity-a"));
      Workflow.sleep(Duration.ofMillis(1), timer(b, "activity-b"));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 3. Explicit Event Group Scopes (`EG-SCOPE`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  // FIXME: Event Group Scopes are not yet supported in Java.

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 4. Implicit Event Groups (`EG-IMPLICIT`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  // FIXME: Event Group Scopes are not yet supported in Java.

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 5. Event Group Marker Aggregation (`EG-AGGREGATION`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void directlyAttachedMarkersDedupeById() {
    List<HistoryEvent> events = aggregationHistory();

    // EG-AGGREGATION-00: Duplicate markers directly attached to one command collapse into a set
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "direct-duplicates"), "aaa", "b-id");

    // EG-AGGREGATION-03: The same group instance listed twice contributes one marker
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "same-instance-twice"), "aaa");

    // EG-AGGREGATION-04: Aggregation keys label markers by ID, disregarding labels
    HistoryEvent sameId = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "same-id-direct");
    assertLabelGroups(sameId, "b-id");
    assertEquals("bbb1", labelText(onlyLabel(sameId)));
  }

  private List<HistoryEvent> aggregationHistory() {
    AggregationWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(AggregationWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface AggregationWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class AggregationWorkflowImpl implements AggregationWorkflow {
    @Override
    public void execute() {
      // Same ID, no label => one group
      EventGroup a1 = Workflow.newEventGroup("aaa");
      EventGroup a2 = Workflow.newEventGroup("aaa");

      // Same ID, different labels => also one group
      EventGroup b1 =
          Workflow.newEventGroup("b-id", EventGroupOptions.newBuilder().setLabel("bbb1").build());
      EventGroup b2 =
          Workflow.newEventGroup("b-id", EventGroupOptions.newBuilder().setLabel("bbb2").build());
      
      Workflow.sleep(
          Duration.ofMillis(1),
          TimerOptions.newBuilder()
              .setSummary("direct-duplicates")
              .addEventGroups(a2, b1, a1, b1, a2, a1)
              .build());
      Workflow.sleep(
          Duration.ofMillis(1),
          TimerOptions.newBuilder()
              .setSummary("same-instance-twice")
              .addEventGroups(a1, a1)
              .build());
      Workflow.sleep(
          Duration.ofMillis(1),
          TimerOptions.newBuilder().setSummary("same-id-direct").addEventGroups(b1, b2).build());
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 6. Command Type Coverage (`EG-COMMANDS`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void timerCommandsCarryMarkers() {
    List<HistoryEvent> events = timerCommandHistory();

    // EG-COMMANDS-00: Timer commands carry markers
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "60s"), "direct");
  }

  @Test
  public void timerCancellationCommandsCarryTheTimersMarkers() {
    List<HistoryEvent> events = timerCommandHistory();

    // EG-COMMANDS-00-CANCEL: Timer Cancellation commands carry Timer's markers
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "60s"), "direct");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_CANCELED, null), "direct");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "1ms"));
  }

  private List<HistoryEvent> timerCommandHistory() {
    TimerCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TimerCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface TimerCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TimerCommandWorkflowImpl implements TimerCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      CancellationScope timer60s =
          Workflow.newCancellationScope(
              () -> Workflow.newTimer(Duration.ofSeconds(60), timer(direct, "60s")).get());
      Promise<Void> running = Async.procedure(timer60s::run);
      Workflow.sleep(Duration.ofMillis(1), timer(null, "1ms"));
      timer60s.cancel();
      swallowCancellation(running);
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void waitConditionsWithTimeoutsCarryMarkersToTheirTimers() {
    List<HistoryEvent> events = awaitHistory();

    // EG-COMMANDS-01: Wait conditions with timeouts carry markers to their timers
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "await"), "direct");
  }

  private List<HistoryEvent> awaitHistory() {
    AwaitWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(AwaitWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface AwaitWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class AwaitWorkflowImpl implements AwaitWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.await(Duration.ofMillis(1), timer(direct, "await"), () -> false);
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////
  // HISTORY HELPERS
  ////////////////////////////////////////////////////////////////////////////////////////////////////

  private List<HistoryEvent> history(Object workflow) {
    WorkflowExecutionId execution = executionOf(workflow);
    return history(workflow, execution.runId);
  }

  private List<HistoryEvent> history(Object workflow, String runId) {
    return testWorkflowRule
        .getWorkflowClient()
        .fetchHistory(executionOf(workflow).workflowId, runId)
        .getEvents();
  }

  private WorkflowExecutionId executionOf(Object workflow) {
    io.temporal.api.common.v1.WorkflowExecution execution =
        workflow instanceof WorkflowStub
            ? ((WorkflowStub) workflow).getExecution()
            : WorkflowStub.fromTyped(workflow).getExecution();
    return new WorkflowExecutionId(execution.getWorkflowId(), execution.getRunId());
  }

  private static HistoryEvent require(List<HistoryEvent> events, EventType type, String summary) {
    for (HistoryEvent event : events) {
      if (event.getEventType() == type && (summary == null || summary.equals(summaryOf(event)))) {
        return event;
      }
    }
    fail("no " + type + " with summary " + summary);
    return null;
  }

  private static void assertLabelGroups(HistoryEvent event, String... ids) {
    Set<String> actual = new HashSet<>();
    for (EventGroupMarker marker : event.getEventGroupMarkersList()) {
      if (marker.hasLabel()) {
        actual.add(marker.getLabel().getId());
      }
    }
    assertEquals(new HashSet<>(Arrays.asList(ids)), actual);
  }

  private static EventGroupMarker onlyLabel(HistoryEvent event) {
    EventGroupMarker found = null;
    for (EventGroupMarker marker : event.getEventGroupMarkersList()) {
      if (!marker.hasLabel()) {
        continue;
      }
      if (found != null) {
        fail("expected one label marker on " + event.getEventId());
      }
      found = marker;
    }
    if (found == null) {
      fail("expected a label marker on " + event.getEventId());
    }
    return found;
  }

  private static String labelText(EventGroupMarker marker) {
    if (!marker.getLabel().hasLabel()) {
      return null;
    }
    return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
        marker.getLabel().getLabel(), String.class, String.class);
  }

  private static String summaryOf(HistoryEvent event) {
    if (!event.hasUserMetadata() || !event.getUserMetadata().hasSummary()) {
      return "";
    }
    return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
        event.getUserMetadata().getSummary(), String.class, String.class);
  }

  private static final class WorkflowExecutionId {
    private final String workflowId;
    private final String runId;

    private WorkflowExecutionId(String workflowId, String runId) {
      this.workflowId = workflowId;
      this.runId = runId;
    }
  }

  private static TimerOptions timer(EventGroup direct, String summary) {
    TimerOptions.Builder builder = TimerOptions.newBuilder().setSummary(summary);
    if (direct != null) {
      builder.addEventGroups(direct);
    }
    return builder.build();
  }

  private static void swallowCancellation(Promise<Void> promise) {
    try {
      promise.get();
    } catch (CanceledFailure expected) {
      // The cancelled command's failure is the point of the test.
    }
  }
}
