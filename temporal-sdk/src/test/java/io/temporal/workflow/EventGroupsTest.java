package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests for Event Groups.
 *
 * <p>Implicit Event Groups (`EG-IMPLICIT`) are not covered: Java does not yet create implicit Event
 * Groups for signal and update handlers.
 */
public class EventGroupsTest {

  private final Activities activities = new Activities();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              // Emits the TemporalChangeVersion upsert that EG-COMMANDS-23 expects to inherit the
              // version marker's Event Groups.
              WorkflowImplementationOptions.newBuilder()
                  .setEnableUpsertVersionSearchAttributes(true)
                  .build(),
              LabelIdWorkflowImpl.class,
              LabelPayloadWorkflowImpl.class,
              ScopeWorkflowImpl.class,
              NestedScopesWorkflowImpl.class,
              ReenteredScopeWorkflowImpl.class,
              ConcurrentScopesWorkflowImpl.class,
              ScopeKeptAfterExitWorkflowImpl.class,
              ScopeNotInheritedWorkflowImpl.class,
              ScopeUnwindsWorkflowImpl.class,
              AggregationWorkflowImpl.class,
              TimerCommandWorkflowImpl.class,
              AwaitWorkflowImpl.class,
              ActivityCommandWorkflowImpl.class,
              LocalActivityCommandWorkflowImpl.class,
              LocalActivityBackoffCommandWorkflowImpl.class,
              ChildCommandWorkflowImpl.class,
              ChildWorkflowImpl.class,
              ChildWorkflowSleep5sImpl.class,
              SignalCommandWorkflowImpl.class,
              ChildWorkflowWaitForSignalsImpl.class,
              CancelExternalCommandWorkflowImpl.class,
              UpsertMemoCommandWorkflowImpl.class,
              UpsertSearchAttributesCommandWorkflowImpl.class,
              VersionCommandWorkflowImpl.class,
              SideEffectCommandWorkflowImpl.class,
              MutableSideEffectCommandWorkflowImpl.class,
              ContinueAsNewCommandWorkflowImpl.class)
          .setActivityImplementations(activities)
          .build();

  @Before
  public void resetActivities() {
    activities.hold = new CountDownLatch(1);
  }

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

  @Test
  public void commandsInAnEventGroupScopeCarryItsMarker() {
    List<HistoryEvent> events = scopeHistory();

    // EG-SCOPE-00: Commands in an Event Group scope carry its marker
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED, "activity"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "timer"), "aaa");
    assertLabelGroups(requireChild(events, "child-workflow"), "aaa");
  }

  private List<HistoryEvent> scopeHistory() {
    ScopeWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(ScopeWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ScopeWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ScopeWorkflowImpl implements ScopeWorkflow {
    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      a.run(
          () -> {
            Workflow.newActivityStub(EventGroupActivities.class, activity(null, "activity")).run();
            Workflow.sleep(Duration.ofMillis(1), timer(null, "timer"));
            Workflow.newChildWorkflowStub(ChildWorkflow.class, child(null, "child-workflow"))
                .execute();
          });
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void nestingEventGroupScopesComposesCorrectly() {
    List<HistoryEvent> events = nestedScopesHistory();

    // EG-SCOPE-01: Nesting Event Group Scopes composes correctly
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-before"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-b"), "aaa", "bbb");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-after"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "outside"));
  }

  private List<HistoryEvent> nestedScopesHistory() {
    NestedScopesWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(NestedScopesWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface NestedScopesWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class NestedScopesWorkflowImpl implements NestedScopesWorkflow {
    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      EventGroup b = Workflow.newEventGroup("bbb");
      a.run(
          () -> {
            Workflow.sleep(Duration.ofMillis(1), timer(null, "a-before"));
            b.run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "a-b")));
            Workflow.sleep(Duration.ofMillis(1), timer(null, "a-after"));
          });
      Workflow.sleep(Duration.ofMillis(1), timer(null, "outside"));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void reenteringAnEventGroupInstanceNestsCorrectly() {
    List<HistoryEvent> events = reenteredScopeHistory();

    // EG-SCOPE-02: Re-entering an Event Group instance nests correctly
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-before"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-inner"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-after"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "outside"));
  }

  private List<HistoryEvent> reenteredScopeHistory() {
    ReenteredScopeWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ReenteredScopeWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ReenteredScopeWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ReenteredScopeWorkflowImpl implements ReenteredScopeWorkflow {
    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      a.run(
          () -> {
            Workflow.sleep(Duration.ofMillis(1), timer(null, "a-before"));
            a.run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "a-inner")));
            Workflow.sleep(Duration.ofMillis(1), timer(null, "a-after"));
          });
      Workflow.sleep(Duration.ofMillis(1), timer(null, "outside"));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void anEventGroupInstanceCanBeScopedConcurrentlyFromTwoBranches() {
    List<HistoryEvent> events = concurrentScopesHistory();

    // EG-SCOPE-03: An Event Group instance can be scoped concurrently from two branches
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "b-a-c"), "bbb", "aaa", "ccc");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "b-a"), "bbb", "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "b-after-a"), "bbb");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "d-a-e"), "ddd", "aaa", "eee");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "d-a"), "ddd", "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "d-after-a"), "ddd");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "outside"));
  }

  private List<HistoryEvent> concurrentScopesHistory() {
    ConcurrentScopesWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ConcurrentScopesWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ConcurrentScopesWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ConcurrentScopesWorkflowImpl implements ConcurrentScopesWorkflow {
    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      EventGroup b = Workflow.newEventGroup("bbb");
      EventGroup c = Workflow.newEventGroup("ccc");
      EventGroup d = Workflow.newEventGroup("ddd");
      EventGroup e = Workflow.newEventGroup("eee");

      Promise<Void> left =
          Async.procedure(
              () ->
                  b.run(
                      () -> {
                        a.run(
                            () -> {
                              c.run(
                                  () -> Workflow.sleep(Duration.ofMillis(1), timer(null, "b-a-c")));
                              Workflow.sleep(Duration.ofMillis(1), timer(null, "b-a"));
                            });
                        Workflow.sleep(Duration.ofMillis(1), timer(null, "b-after-a"));
                      }));
      Promise<Void> right =
          Async.procedure(
              () ->
                  d.run(
                      () -> {
                        a.run(
                            () -> {
                              e.run(
                                  () -> Workflow.sleep(Duration.ofMillis(1), timer(null, "d-a-e")));
                              Workflow.sleep(Duration.ofMillis(1), timer(null, "d-a"));
                            });
                        Workflow.sleep(Duration.ofMillis(1), timer(null, "d-after-a"));
                      }));
      Promise.allOf(left, right).get();

      Workflow.sleep(Duration.ofMillis(1), timer(null, "outside"));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void aTaskStartedInsideAScopeKeepsItAfterTheScopeExits() {
    List<HistoryEvent> events = scopeKeptAfterExitHistory();

    // EG-SCOPE-04: A task started inside a scope keeps it after the scope exits
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "inside-before"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "inside-after"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "outside"));
  }

  private List<HistoryEvent> scopeKeptAfterExitHistory() {
    ScopeKeptAfterExitWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ScopeKeptAfterExitWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ScopeKeptAfterExitWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ScopeKeptAfterExitWorkflowImpl implements ScopeKeptAfterExitWorkflow {
    private boolean scopeExited;

    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      Promise<Void> task =
          a.run(
              () ->
                  Async.procedure(
                      () -> {
                        Workflow.sleep(Duration.ofMillis(1), timer(null, "inside-before"));
                        Workflow.await(() -> scopeExited);
                        Workflow.sleep(Duration.ofMillis(1), timer(null, "inside-after"));
                      }));
      scopeExited = true;
      task.get();

      Workflow.sleep(Duration.ofMillis(1), timer(null, "outside"));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void aTaskCreatedOutsideAScopeDoesNotInheritItWhenResumedInside() {
    List<HistoryEvent> events = scopeNotInheritedHistory();

    // EG-SCOPE-05: A task created outside a scope does not inherit it when resumed inside
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "outside-task"));
  }

  private List<HistoryEvent> scopeNotInheritedHistory() {
    ScopeNotInheritedWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ScopeNotInheritedWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ScopeNotInheritedWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ScopeNotInheritedWorkflowImpl implements ScopeNotInheritedWorkflow {
    private boolean scopeEntered;

    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      Promise<Void> task =
          Async.procedure(
              () -> {
                Workflow.await(() -> scopeEntered);
                Workflow.sleep(Duration.ofMillis(1), timer(null, "outside-task"));
              });
      a.run(
          () -> {
            scopeEntered = true;
            task.get();
          });
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void anEventGroupScopeUnwindsCleanlyWhenItsBodyThrows() {
    List<HistoryEvent> events = scopeUnwindsHistory();

    // EG-SCOPE-06: An Event Group scope unwinds cleanly when its body throws
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-b"), "aaa", "bbb");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-after"), "aaa");
  }

  private List<HistoryEvent> scopeUnwindsHistory() {
    ScopeUnwindsWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ScopeUnwindsWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ScopeUnwindsWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ScopeUnwindsWorkflowImpl implements ScopeUnwindsWorkflow {
    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      EventGroup b = Workflow.newEventGroup("bbb");
      a.run(
          () -> {
            try {
              b.run(
                  () -> {
                    Workflow.sleep(Duration.ofMillis(1), timer(null, "a-b"));
                    throw new IllegalStateException("boom");
                  });
            } catch (IllegalStateException expected) {
              // The point of the test is what the scope looks like once this unwinds.
            }
            Workflow.sleep(Duration.ofMillis(1), timer(null, "a-after"));
          });
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 4. Implicit Event Groups (`EG-IMPLICIT`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  // FIXME: Implicit Event Groups are not yet supported in Java.

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 5. Event Group Marker Aggregation (`EG-AGGREGATION`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void markersDedupeByIdWhetherScopedOrDirectlyAttached() {
    List<HistoryEvent> events = aggregationHistory();

    // EG-AGGREGATION-00: Duplicate markers directly attached to one command collapse into a set
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "direct-duplicates"), "aaa", "b-id");

    // EG-AGGREGATION-01: Nested scopes of duplicate groups collapse into a set
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "nested-scopes"), "aaa", "b-id");

    // EG-AGGREGATION-02: Scoped and directly attached markers collapse into one set
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "scope-and-direct-b"), "aaa", "b-id");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "scope-and-direct-a-b"), "aaa", "b-id");

    // EG-AGGREGATION-03: The same group instance listed twice contributes one marker
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "same-instance-twice"), "aaa");

    // EG-AGGREGATION-04: Aggregation keys label markers by ID, disregarding labels
    HistoryEvent sameId = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "same-id-direct");
    assertLabelGroups(sameId, "b-id");
    assertEquals("bbb1", labelText(onlyLabel(sameId)));
    HistoryEvent sameIdScoped =
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "same-id-scope-and-direct");
    assertLabelGroups(sameIdScoped, "b-id");
    assertEquals("bbb1", labelText(onlyLabel(sameIdScoped)));
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
      a1.run(
          () ->
              a2.run(
                  () ->
                      b1.run(
                          () ->
                              Workflow.sleep(Duration.ofMillis(1), timer(null, "nested-scopes")))));
      a1.run(
          () ->
              b1.run(
                  () -> {
                    Workflow.sleep(Duration.ofMillis(1), timer(b1, "scope-and-direct-b"));
                    Workflow.sleep(
                        Duration.ofMillis(1),
                        TimerOptions.newBuilder()
                            .setSummary("scope-and-direct-a-b")
                            .addEventGroups(b1, a1)
                            .build());
                  }));
      Workflow.sleep(
          Duration.ofMillis(1),
          TimerOptions.newBuilder()
              .setSummary("same-instance-twice")
              .addEventGroups(a1, a1)
              .build());
      Workflow.sleep(
          Duration.ofMillis(1),
          TimerOptions.newBuilder().setSummary("same-id-direct").addEventGroups(b1, b2).build());
      b1.run(() -> Workflow.sleep(Duration.ofMillis(1), timer(b2, "same-id-scope-and-direct")));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 6. Command Type Coverage (`EG-COMMANDS`)
  //
  // EG-COMMANDS-05 and 05-CANCEL are in EventGroupsNexusTest: they need a Nexus endpoint.
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

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void activityCommandsCarryMarkers() {
    List<HistoryEvent> events = activityCommandHistory();

    // EG-COMMANDS-02: Activity commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED, "activity"), "direct");
  }

  @Test
  public void activityCancellationCommandsCarryTheActivitysMarkers() {
    List<HistoryEvent> events = activityCommandHistory();

    // EG-COMMANDS-02-CANCEL: Activity Cancellation commands carry Activity's markers
    assertLabelGroups(
        require(
            events, EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED, "activity-cancelled-sleep-5s"),
        "direct");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED, null), "direct");
  }

  private List<HistoryEvent> activityCommandHistory() {
    ActivityCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ActivityCommandWorkflow.class);
    try {
      workflow.execute();
    } finally {
      activities.hold.countDown();
    }
    return history(workflow);
  }

  @WorkflowInterface
  public interface ActivityCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ActivityCommandWorkflowImpl implements ActivityCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.newActivityStub(EventGroupActivities.class, activity(direct, "activity")).run();

      CancellationScope cancellation =
          Workflow.newCancellationScope(
              () ->
                  Workflow.newActivityStub(
                          EventGroupActivities.class,
                          activity(direct, "activity-cancelled-sleep-5s").toBuilder()
                              .setCancellationType(ActivityCancellationType.TRY_CANCEL)
                              .build())
                      .sleepUntilCancelled());
      Promise<Void> running = Async.procedure(cancellation::run);
      // A Workflow Task boundary so the schedule command reaches the server before cancellation.
      Workflow.sleep(Duration.ofMillis(1));
      cancellation.cancel();
      swallowCancellation(running);
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void localActivityCommandsCarryMarkers() {
    List<HistoryEvent> events = localActivityCommandHistory();

    // EG-COMMANDS-03: Local Activity commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_MARKER_RECORDED, "local-activity"), "direct");

    // EG-COMMANDS-03-CANCEL is skipped: LocalActivityStateMachine.cancel is a no-op,
    // so a cancellation request produces no cancellation marker.
    // assertLabelGroups(require(events, EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED, null),
    // "direct");
  }

  private List<HistoryEvent> localActivityCommandHistory() {
    LocalActivityCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(LocalActivityCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface LocalActivityCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class LocalActivityCommandWorkflowImpl implements LocalActivityCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.newLocalActivityStub(
              EventGroupActivities.class, localActivity(direct, "local-activity"))
          .localActivity();
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void localActivityRetryBackoffTimerCarriesTheLasMarkers() {
    List<HistoryEvent> events = localActivityBackoffHistory();

    List<HistoryEvent> markers = new ArrayList<>();
    for (HistoryEvent event : events) {
      if (event.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED
          && "backoff-local-activity-fail-first-attempt".equals(summaryOf(event))) {
        markers.add(event);
      }
    }
    assertEquals(2, markers.size());

    // EG-COMMANDS-03-BACKOFF: Local Activity Retry Backoff Timer carries LA's markers
    assertLabelGroups(markers.get(0), "direct");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, null), "direct");
    assertLabelGroups(markers.get(1), "direct");
  }

  private List<HistoryEvent> localActivityBackoffHistory() {
    LocalActivityBackoffCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(LocalActivityBackoffCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface LocalActivityBackoffCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class LocalActivityBackoffCommandWorkflowImpl
      implements LocalActivityBackoffCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      // A retry interval past localRetryThreshold is what records the backoff as a workflow
      // timer. The plan uses a 10s interval against a 5s workflow task timeout; lowering the
      // threshold reaches the same branch without waiting that long.
      LocalActivityOptions options =
          LocalActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(20))
              .setLocalRetryThreshold(Duration.ofMillis(1))
              .setRetryOptions(
                  RetryOptions.newBuilder()
                      .setInitialInterval(Duration.ofSeconds(1))
                      .setBackoffCoefficient(1)
                      .setMaximumAttempts(2)
                      .build())
              .setSummary("backoff-local-activity-fail-first-attempt")
              .addEventGroups(direct)
              .build();
      Workflow.newLocalActivityStub(EventGroupActivities.class, options).failFirstAttempt();
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void childWorkflowCommandsCarryMarkers() {
    List<HistoryEvent> events = childCommandHistory();

    // EG-COMMANDS-04: Child Workflow commands carry markers
    assertLabelGroups(requireChild(events, "child-workflow"), "direct");
  }

  @Test
  public void childWorkflowCancellationCommandsCarryTheChildWorkflowsMarkers() {
    List<HistoryEvent> events = childCommandHistory();

    // EG-COMMANDS-04-CANCEL: Child Workflow Cancellation commands carry the Child Workflow's
    // markers
    assertLabelGroups(requireChild(events, "child-workflow-sleep-5s"), "direct");
    assertLabelGroups(
        require(
            events,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
            null),
        "direct");
  }

  private List<HistoryEvent> childCommandHistory() {
    ChildCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ChildCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ChildCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ChildCommandWorkflowImpl implements ChildCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.newChildWorkflowStub(ChildWorkflow.class, child(direct, "child-workflow")).execute();

      // The plan uses WAIT_CANCELLATION_REQUESTED, but the test server delivers the cancel request
      // without holding the time-skipping lock, so time can skip to the parent's run timeout while
      // the parent waits for the request to be acknowledged.
      ChildWorkflowSleep5s sleeping =
          Workflow.newChildWorkflowStub(
              ChildWorkflowSleep5s.class,
              child(direct, "child-workflow-sleep-5s").toBuilder()
                  .setCancellationType(ChildWorkflowCancellationType.TRY_CANCEL)
                  .build());
      CancellationScope cancellation = Workflow.newCancellationScope(sleeping::execute);
      Promise<Void> running = Async.procedure(cancellation::run);
      // Cancelling while the child's start is still in flight makes the test server reject the
      // workflow task with UnhandledCommand, and the workflow then times out.
      Workflow.getWorkflowExecution(sleeping).get();
      cancellation.cancel();
      swallowCancellation(running);
    }
  }

  @WorkflowInterface
  public interface ChildWorkflow {
    @WorkflowMethod(name = "child-workflow")
    void execute();
  }

  public static class ChildWorkflowImpl implements ChildWorkflow {
    @Override
    public void execute() {}
  }

  @WorkflowInterface
  public interface ChildWorkflowSleep5s {
    @WorkflowMethod(name = "child-workflow-sleep-5s")
    void execute();
  }

  public static class ChildWorkflowSleep5sImpl implements ChildWorkflowSleep5s {
    @Override
    public void execute() {
      // Stays open so the parent can cancel it before a normal completion.
      Workflow.await(() -> false);
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void signalExternalWorkflowCommandsCarryMarkers() {
    List<HistoryEvent> events = signalCommandHistory();

    // EG-COMMANDS-06: Signal External Workflow commands carry markers
    assertLabelGroups(requireSignal(events, "other-workflow"), "direct");
  }

  @Test
  public void signalChildWorkflowCommandsCarryMarkers() {
    List<HistoryEvent> events = signalCommandHistory();

    // EG-COMMANDS-06-CHILD: Signal Child Workflow commands carry markers
    assertLabelGroups(requireSignal(events, "child-workflow-wait-for-signals"), "direct");
  }

  private List<HistoryEvent> signalCommandHistory() {
    SignalCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SignalCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface SignalCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class SignalCommandWorkflowImpl implements SignalCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      try {
        Workflow.newUntypedExternalWorkflowStub("other-workflow")
            .signal(
                "signal",
                SignalExternalWorkflowOptions.newBuilder().addEventGroups(direct).build());
      } catch (ApplicationFailure expected) {
        // A missing workflow still records the initiated event before the request fails.
      }

      ChildWorkflowWaitForSignals child =
          Workflow.newChildWorkflowStub(
              ChildWorkflowWaitForSignals.class, child(null, "child-workflow-wait-for-signals"));
      Promise<Void> done = Async.procedure(child::execute);
      Workflow.getWorkflowExecution(child).get();
      ChildWorkflowStub.fromTyped(child)
          .signal("signal", SignalChildWorkflowOptions.newBuilder().addEventGroups(direct).build());
      done.get();
    }
  }

  @WorkflowInterface
  public interface ChildWorkflowWaitForSignals {
    @WorkflowMethod(name = "child-workflow-wait-for-signals")
    void execute();

    @SignalMethod(name = "signal")
    void signal();
  }

  public static class ChildWorkflowWaitForSignalsImpl implements ChildWorkflowWaitForSignals {
    private boolean signaled;

    @Override
    public void execute() {
      Workflow.await(() -> signaled);
    }

    @Override
    public void signal() {
      signaled = true;
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void cancelExternalWorkflowCommandsCarryMarkers() {
    List<HistoryEvent> events = cancelExternalCommandHistory();

    // EG-COMMANDS-07: Cancel External Workflow commands carry markers
    assertLabelGroups(
        require(
            events,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
            null),
        "direct");
  }

  private List<HistoryEvent> cancelExternalCommandHistory() {
    CancelExternalCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(CancelExternalCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface CancelExternalCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class CancelExternalCommandWorkflowImpl implements CancelExternalCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      // The test server never resolves a cancel of a missing workflow, so waiting on it runs the
      // workflow out to its start-to-close timeout. The initiated event is recorded either way.
      Async.procedure(
          () ->
              Workflow.newUntypedExternalWorkflowStub("other-workflow")
                  .cancel(
                      CancelExternalWorkflowOptions.newBuilder().addEventGroups(direct).build()));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void modifyWorkflowPropertiesCommandsCarryMarkers() {
    List<HistoryEvent> events = upsertMemoCommandHistory();

    // EG-COMMANDS-20: Modify Workflow Properties commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED, null), "direct");
  }

  private List<HistoryEvent> upsertMemoCommandHistory() {
    UpsertMemoCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UpsertMemoCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface UpsertMemoCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class UpsertMemoCommandWorkflowImpl implements UpsertMemoCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.upsertMemo(
          Collections.singletonMap("some-key", "some-value"),
          UpsertMemoOptions.newBuilder().addEventGroups(direct).build());
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @CloudTestExclusionNote(
      "Cloud CI does not provision the custom search attributes required by this test.")
  @Category(RequiresCloudProvisioning.class)
  @Test
  public void upsertSearchAttributeCommandsCarryMarkers() {
    List<HistoryEvent> events = upsertSearchAttributesCommandHistory();

    // EG-COMMANDS-21: Upsert Search Attribute commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES, null), "direct");
  }

  private List<HistoryEvent> upsertSearchAttributesCommandHistory() {
    UpsertSearchAttributesCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UpsertSearchAttributesCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface UpsertSearchAttributesCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class UpsertSearchAttributesCommandWorkflowImpl
      implements UpsertSearchAttributesCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.upsertTypedSearchAttributes(
          UpsertSearchAttributesOptions.newBuilder().addEventGroups(direct).build(),
          SearchAttributeKey.forKeyword("CustomKeywordField").valueSet("some-value"));
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void versionCommandsCarryMarkers() {
    List<HistoryEvent> events = versionCommandHistory();

    // EG-COMMANDS-23: Version commands carry markers
    assertLabelGroups(require(events, EventType.EVENT_TYPE_MARKER_RECORDED, null), "direct");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES, null), "direct");
  }

  private List<HistoryEvent> versionCommandHistory() {
    VersionCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(VersionCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface VersionCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class VersionCommandWorkflowImpl implements VersionCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.getVersion(
          "my-change",
          Workflow.DEFAULT_VERSION,
          1,
          GetVersionOptions.newBuilder().addEventGroups(direct).build());
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void sideEffectCommandsCarryMarkers() {
    List<HistoryEvent> events = sideEffectCommandHistory();

    // EG-COMMANDS-24: Side Effect commands carry markers
    assertLabelGroups(require(events, EventType.EVENT_TYPE_MARKER_RECORDED, null), "direct");
  }

  private List<HistoryEvent> sideEffectCommandHistory() {
    SideEffectCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SideEffectCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface SideEffectCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class SideEffectCommandWorkflowImpl implements SideEffectCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.sideEffect(
          Integer.class, () -> 42, SideEffectOptions.newBuilder().addEventGroups(direct).build());
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void mutableSideEffectCommandsCarryMarkers() {
    List<HistoryEvent> events = mutableSideEffectCommandHistory();

    // EG-COMMANDS-25: Mutable Side Effect commands carry markers
    assertLabelGroups(require(events, EventType.EVENT_TYPE_MARKER_RECORDED, null), "direct");
  }

  private List<HistoryEvent> mutableSideEffectCommandHistory() {
    MutableSideEffectCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(MutableSideEffectCommandWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface MutableSideEffectCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class MutableSideEffectCommandWorkflowImpl
      implements MutableSideEffectCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.mutableSideEffect(
          "my-id",
          Integer.class,
          (previous, next) -> !previous.equals(next),
          () -> 42,
          MutableSideEffectOptions.newBuilder().addEventGroups(direct).build());
    }
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void continueAsNewCommandsCarryMarkers() {
    List<HistoryEvent> events = continueAsNewCommandHistory();

    // EG-COMMANDS-40: Continue-As-New carries directly attached markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW, null), "direct");
  }

  private List<HistoryEvent> continueAsNewCommandHistory() {
    ContinueAsNewCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ContinueAsNewCommandWorkflow.class);
    return history(workflow, workflow.execute());
  }

  @WorkflowInterface
  public interface ContinueAsNewCommandWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class ContinueAsNewCommandWorkflowImpl implements ContinueAsNewCommandWorkflow {
    @Override
    public String execute() {
      // The markers stay on the first run, which the client stub no longer points at once the
      // workflow has continued as new.
      Optional<String> firstRunId = Workflow.getInfo().getContinuedExecutionRunId();
      if (firstRunId.isPresent()) {
        return firstRunId.get();
      }
      EventGroup direct = Workflow.newEventGroup("direct");
      Workflow.continueAsNew(ContinueAsNewOptions.newBuilder().addEventGroups(direct).build());
      return "unreachable";
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////
  // SHARED ACTIVITIES
  ////////////////////////////////////////////////////////////////////////////////////////////////////

  @ActivityInterface
  public interface EventGroupActivities {
    @ActivityMethod(name = "activity")
    void run();

    @ActivityMethod(name = "activity-cancelled-sleep-5s")
    void sleepUntilCancelled();

    @ActivityMethod(name = "local-activity")
    void localActivity();

    @ActivityMethod(name = "backoff-local-activity-fail-first-attempt")
    void failFirstAttempt();
  }

  public static class Activities implements EventGroupActivities {
    // Released after the workflow returns, so the cancelled activity does not complete into a
    // workflow task that is still running.
    private CountDownLatch hold = new CountDownLatch(1);

    @Override
    public void run() {}

    @Override
    public void sleepUntilCancelled() {
      try {
        if (!hold.await(15, TimeUnit.SECONDS)) {
          throw new IllegalStateException("cancelled activity was not released");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw Activity.wrap(e);
      }
    }

    @Override
    public void localActivity() {}

    @Override
    public void failFirstAttempt() {
      if (Activity.getExecutionContext().getInfo().getAttempt() == 1) {
        throw ApplicationFailure.newFailure("flaky activity failing on purpose", "flaky");
      }
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

  private static HistoryEvent requireChild(List<HistoryEvent> events, String workflowType) {
    for (HistoryEvent event : events) {
      if (event.getEventType() == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED
          && workflowType.equals(
              event
                  .getStartChildWorkflowExecutionInitiatedEventAttributes()
                  .getWorkflowType()
                  .getName())) {
        return event;
      }
    }
    fail("no child start for " + workflowType);
    return null;
  }

  private static HistoryEvent requireSignal(List<HistoryEvent> events, String workflowIdSuffix) {
    for (HistoryEvent event : events) {
      if (event.getEventType() == EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED
          && event
              .getSignalExternalWorkflowExecutionInitiatedEventAttributes()
              .getWorkflowExecution()
              .getWorkflowId()
              .endsWith(workflowIdSuffix)) {
        return event;
      }
    }
    fail("no signal to " + workflowIdSuffix);
    return null;
  }

  private static ChildWorkflowOptions child(EventGroup direct, String workflowType) {
    ChildWorkflowOptions.Builder builder =
        ChildWorkflowOptions.newBuilder()
            .setTaskQueue(Workflow.getInfo().getTaskQueue())
            .setWorkflowId(Workflow.getInfo().getWorkflowId() + "-" + workflowType);
    if (direct != null) {
      builder.addEventGroups(direct);
    }
    return builder.build();
  }

  private static LocalActivityOptions localActivity(EventGroup direct, String summary) {
    return LocalActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(20))
        .setSummary(summary)
        .addEventGroups(direct)
        .build();
  }

  private static ActivityOptions activity(EventGroup direct, String summary) {
    ActivityOptions.Builder builder =
        ActivityOptions.newBuilder()
            .setScheduleToStartTimeout(Duration.ofSeconds(10))
            .setStartToCloseTimeout(Duration.ofSeconds(20))
            .setSummary(summary);
    if (direct != null) {
      builder.addEventGroups(direct);
    }
    return builder.build();
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
    } catch (CanceledFailure | ActivityFailure | ChildWorkflowFailure expected) {
      // The cancelled command's failure is the point of the test.
    }
  }
}
