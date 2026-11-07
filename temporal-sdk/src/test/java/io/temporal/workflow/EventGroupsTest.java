package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/** Tests for Event Groups. */
public class EventGroupsTest {

  private final Activities activities = new Activities();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setEnableUpsertVersionSearchAttributes(true)
                  .build(),
              CancelWorkflowImpl.class,
              AwaitWorkflowImpl.class,
              ActivityWorkflowImpl.class,
              ActivityCancelWorkflowImpl.class,
              ChildWorkflowImpl.class,
              QuickChildImpl.class,
              ChildCancelWorkflowImpl.class,
              WaitingChildImpl.class,
              SignalTargetImpl.class,
              SignalExternalWorkflowImpl.class,
              ChildSignalWorkflowImpl.class,
              CancelExternalWorkflowImpl.class,
              UpsertMemoWorkflowImpl.class,
              UpsertSearchAttributesWorkflowImpl.class,
              VersionWorkflowImpl.class,
              SideEffectWorkflowImpl.class,
              MutableSideEffectWorkflowImpl.class,
              ScopeWorkflowImpl.class,
              ImplicitWorkflowImpl.class,
              RuntimeSignalWorkflowImpl.class,
              RuntimeUpdateWorkflowImpl.class)
          .setActivityImplementations(activities)
          .build();

  @Before
  public void resetActivities() {
    activities.started = new CountDownLatch(1);
    activities.release = new CountDownLatch(1);
    activities.hold = new CountDownLatch(1);
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 1. Explicit Event Groups Marker Label IDs (`EG-LABEL-ID`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void eventGroupIdsAreUsedVerbatim() {
    List<HistoryEvent> events = scopeHistory();
    HistoryEvent verbatim = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "verbatim");

    // EG-LABEL-ID-20: IDs are used verbatim
    assertEquals("custom id / already", onlyLabel(verbatim).getLabel().getId());

    // EG-LABEL-ID-22: Same label + different IDs => distinct groups
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "other-id"), "other");
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 2. Explicit Event Groups Marker Label Payload (`EG-LABEL-PAYLOAD`)
  //
  // EG-LABEL-PAYLOAD-20 and 21 are in EventGroupsCodecTest: they need a payload codec.
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void eventGroupLabelsConvertToPayloadsAsJsonStrings() {
    List<HistoryEvent> events = scopeHistory();

    HistoryEvent hello = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "labeled");
    Payload payload = onlyLabel(hello).getLabel().getLabel();

    // EG-LABEL-PAYLOAD-00: Label Payload converts to a json/plain JSON string
    assertEquals("json/plain", payload.getMetadataOrThrow("encoding").toStringUtf8());
    assertEquals(
        "hello",
        DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, String.class, String.class));

    // EG-LABEL-PAYLOAD-02: An omitted label produces no payload
    assertNull(
        labelText(onlyLabel(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "no-label"))));
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 3. Explicit Event Group Scopes (`EG-SCOPE`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void commandsInAnEventGroupScopeCarryItsMarker() {
    List<HistoryEvent> events = scopeHistory();

    // EG-SCOPE-00: Commands in an Event Group scope carry its marker
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-before"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "reenter"), "aaa");
  }

  @Test
  public void nestingEventGroupScopesComposesCorrectly() {
    List<HistoryEvent> events = scopeHistory();

    // EG-SCOPE-01: Nesting Event Group Scopes composes correctly
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-before"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-b"), "aaa", "bbb");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "a-after"), "aaa");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "outside"));
  }

  @Test
  public void reenteringAnEventGroupInstanceNestsCorrectly() {
    List<HistoryEvent> events = scopeHistory();

    // EG-SCOPE-02: Re-entering an Event Group instance nests correctly
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "nested-duplicate"), "a1");
  }

  @Test
  public void anEventGroupInstanceCanBeScopedConcurrentlyFromTwoBranches() {
    List<HistoryEvent> events = scopeHistory();

    // EG-SCOPE-03: An Event Group instance can be scoped concurrently from two branches
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "branch-a"), "scope", "branch-a");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "branch-b"), "scope", "inner");
  }

  @Test
  public void aTaskStartedInsideAScopeKeepsItAfterTheScopeExits() {
    List<HistoryEvent> events = scopeHistory();

    // EG-SCOPE-04: A task started inside a scope keeps it after the scope exits
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "kept-after-exit"), "kept");
  }

  @Test
  public void aTaskCreatedOutsideAScopeDoesNotInheritItWhenResumedInside() {
    List<HistoryEvent> events = scopeHistory();

    // EG-SCOPE-05: A task created outside a scope does not inherit it when resumed inside
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "outside-thread"));
  }

  @Test
  public void anEventGroupScopeUnwindsCleanlyWhenItsBodyThrows() {
    List<HistoryEvent> events = scopeHistory();

    // EG-SCOPE-06: An Event Group scope unwinds cleanly when its body throws
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "inside-throw"), "boom");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "after-throw"));
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 4. Implicit Event Groups (`EG-IMPLICIT`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void implicitScopeOnStaticallyDeclaredSignalHandlerExistsAndComposesAppropriately()
      throws Exception {
    List<HistoryEvent> events = implicitHistory();

    long signalEventId = signaledEventId(events, "onSignal");
    HistoryEvent inHandler = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "in-signal");

    // EG-IMPLICIT-00: A statically declared signal handler carries the signaled event's marker
    assertEquals(signalEventId, inboundEventId(inHandler));

    // EG-IMPLICIT-01: An explicit scope composes with a statically declared signal handler's
    // implicit scope
    assertLabelGroups(inHandler, "inside-signal");

    // EG-IMPLICIT-30: Signal implicit scope does not leak to commands in the Workflow main function
    assertEquals(
        0,
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "main-after-signal")
            .getEventGroupMarkersCount());
  }

  @Test
  public void implicitScopeOnStaticallyDeclaredUpdateHandlerExistsAndComposesAppropriately()
      throws Exception {
    List<HistoryEvent> events = implicitHistory();

    String updateId = acceptedUpdateId(events, "upd");
    HistoryEvent inUpdate = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "in-update");

    // EG-IMPLICIT-50: A statically declared update handler carries the update ID
    assertEquals(updateId, inboundUpdateId(inUpdate));

    // EG-IMPLICIT-51: An explicit scope composes with a statically declared update handler's
    // implicit scope
    assertLabelGroups(inUpdate, "inside-update");

    // EG-IMPLICIT-80: Update implicit scope does not leak to commands in the Workflow main function
    assertEquals(
        0,
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "main-after-update")
            .getEventGroupMarkersCount());
  }

  // The dynamic handler also receives the buffered named signal, so its first timer carries the
  // wrong inbound event. See https://github.com/temporalio/sdk-java/issues/3189.
  @Ignore("https://github.com/temporalio/sdk-java/issues/3189")
  @Test
  public void implicitScopeOnRuntimeRegisteredSignalHandlerExistsAndComposesAppropriately()
      throws Exception {
    RuntimeSignalWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(RuntimeSignalWorkflow.class);
    CompletableFuture<Void> done = WorkflowClient.execute(workflow::execute);
    assertTrue(activities.started.await(10, TimeUnit.SECONDS));
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    untyped.signal("named", "x");
    untyped.signal("dyn", "y");
    activities.release.countDown();
    done.get(10, TimeUnit.SECONDS);
    List<HistoryEvent> events = history(workflow);

    HistoryEvent named = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "named-handler");

    // EG-IMPLICIT-10: A runtime-registered signal handler does not inherit its registration scope
    // EG-IMPLICIT-11: An explicit scope composes with a runtime-registered signal handler's
    // implicit scope
    assertEquals(signaledEventId(events, "named"), inboundEventId(named));
    assertLabelGroups(named, "inside");
    HistoryEvent dynamic = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "dynamic-handler");

    // EG-IMPLICIT-20: A catch-all signal handler carries the signaled event's marker
    assertEquals(signaledEventId(events, "dyn"), inboundEventId(dynamic));

    // EG-IMPLICIT-10: A runtime-registered signal handler does not inherit its registration scope
    assertLabelGroups(dynamic);

    // EG-IMPLICIT-30: Signal implicit scope does not leak to commands in the Workflow main function
    assertEquals(
        0,
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "main-after")
            .getEventGroupMarkersCount());
  }

  @Test
  public void implicitScopeOnRuntimeRegisteredUpdateHandlerExistsAndComposesAppropriately()
      throws Exception {
    RuntimeUpdateWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(RuntimeUpdateWorkflow.class);
    CompletableFuture<Void> done = WorkflowClient.execute(workflow::execute);

    // Updates are rejected until the handler exists, so wait until registration has blocked.
    assertTrue(activities.started.await(10, TimeUnit.SECONDS));
    WorkflowStub.fromTyped(workflow).update("upd", Void.class, "x");
    activities.release.countDown();
    done.get(10, TimeUnit.SECONDS);

    List<HistoryEvent> events = history(workflow);
    HistoryEvent handler = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "in-runtime-update");

    // EG-IMPLICIT-60: A runtime-registered update handler does not inherit its registration scope
    // EG-IMPLICIT-61: An explicit scope composes with a runtime-registered update handler's
    // implicit scope
    assertEquals(acceptedUpdateId(events, "upd"), inboundUpdateId(handler));
    assertLabelGroups(handler, "inside");

    // EG-IMPLICIT-80: Update implicit scope does not leak to commands in the Workflow main function
    assertEquals(
        0,
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "main-after")
            .getEventGroupMarkersCount());
  }

  @Test
  public void defaultSignalAndUpdateHandlersReceiveTheSameImplicitEventGroup() throws Exception {
    List<HistoryEvent> events = implicitHistory();

    String dynamicUpdateId = acceptedUpdateId(events, "dynupd");
    HistoryEvent dynamic = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "dynamic-update");

    // EG-IMPLICIT-70: A catch-all update handler carries the update ID
    assertEquals(dynamicUpdateId, inboundUpdateId(dynamic));
    assertLabelGroups(dynamic);
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 5. Event Group Marker Aggregation (`EG-AGGREGATION`)
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void markersDedupeByIdWhetherScopedOrDirectlyAttached() {
    List<HistoryEvent> events = scopeHistory();

    // EG-AGGREGATION-02: Scoped and directly attached markers collapse into one set
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "scope-and-direct"), "a1", "b");

    // EG-AGGREGATION-03: The same group instance listed twice contributes one marker
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "same-instance-twice"), "a1");
    HistoryEvent sameId = require(events, EventType.EVENT_TYPE_TIMER_STARTED, "same-id-direct");

    // EG-AGGREGATION-04: Aggregation keys label markers by ID, disregarding labels
    assertLabelGroups(sameId, "b");
    assertEquals("b1", labelText(onlyLabel(sameId)));
    HistoryEvent scopeWins =
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "same-id-scope-and-direct");
    assertLabelGroups(scopeWins, "b");
    assertEquals("b1", labelText(onlyLabel(scopeWins)));
  }

  // ////////////////////////////////////////////////////////////////////////////////////////////////
  // 6. Command Type Coverage (`EG-COMMANDS`)
  //
  // EG-COMMANDS-05 and 05-CANCEL are in EventGroupsNexusTest: they need a Nexus endpoint.
  // ////////////////////////////////////////////////////////////////////////////////////////////////

  @Test
  public void timerCommandsCarryMarkers() {
    List<HistoryEvent> events = cancelHistory();

    // EG-COMMANDS-00: Timer commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "long-timer"), "direct", "scope");
  }

  @Test
  public void timerCancellationCommandsCarryTheTimersMarkers() {
    List<HistoryEvent> events = cancelHistory();

    // EG-COMMANDS-00-CANCEL: Timer Cancellation commands carry Timer's markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "long-timer"), "direct", "scope");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_CANCELED, null), "direct", "scope");
    assertLabelGroups(require(events, EventType.EVENT_TYPE_TIMER_STARTED, "timeout"), "scope");
  }

  @Test
  public void waitConditionsWithTimeoutsCarryMarkersToTheirTimers() {
    List<HistoryEvent> events = awaitHistory();

    // EG-COMMANDS-01: Wait conditions with timeouts carry markers to their timers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_TIMER_STARTED, "await"), "direct", "scope");
  }

  @Test
  public void activityCommandsCarryMarkers() {
    List<HistoryEvent> events = activityHistory();

    // EG-COMMANDS-02: Activity commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED, "activity"),
        "direct",
        "scope");
  }

  @Test
  public void activityCancellationCommandsCarryTheActivitysMarkers() {
    List<HistoryEvent> events = activityCancelHistory();

    // EG-COMMANDS-02-CANCEL: Activity Cancellation commands carry Activity's markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED, "activity"),
        "direct",
        "scope");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED, null),
        "direct",
        "scope");
  }

  @Test
  public void childWorkflowCommandsCarryMarkers() {
    List<HistoryEvent> events = childHistory();

    // EG-COMMANDS-04: Child Workflow commands carry markers
    assertLabelGroups(requireChild(events, "child"), "direct", "scope");
  }

  @Test
  public void childWorkflowCancellationCommandsCarryTheChildWorkflowsMarkers() {
    List<HistoryEvent> events = childCancelHistory();

    // EG-COMMANDS-04-CANCEL: Child Workflow Cancellation commands carry the Child Workflow's
    // markers
    assertLabelGroups(requireChild(events, "child"), "direct", "scope");
    assertLabelGroups(
        require(
            events,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
            null),
        "direct",
        "scope");
  }

  @Test
  public void signalExternalWorkflowCommandsCarryMarkers() {
    List<HistoryEvent> events = signalExternalHistory();

    // EG-COMMANDS-06: Signal External Workflow commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED, null),
        "direct",
        "scope");
  }

  @Test
  public void childWorkflowSignalCommandsCarryMarkers() {
    List<HistoryEvent> events = childSignalHistory();

    // EG-COMMANDS-06-CHILD: Child Workflow signal commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED, null),
        "direct",
        "scope");
  }

  @Test
  public void cancelExternalWorkflowCommandsCarryMarkers() {
    List<HistoryEvent> events = cancelExternalHistory();

    // EG-COMMANDS-07: Cancel External Workflow commands carry markers
    assertLabelGroups(
        require(
            events,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
            null),
        "direct",
        "scope");
  }

  @Test
  public void modifyWorkflowPropertiesCommandsCarryMarkers() {
    List<HistoryEvent> events = upsertMemoHistory();

    // EG-COMMANDS-20: Modify Workflow Properties commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED, null),
        "direct",
        "scope");
  }

  @Test
  public void versionCommandsCarryTheAmbientScope() {
    List<HistoryEvent> events = versionHistory();

    // EG-COMMANDS-23: Version commands carry the ambient scope
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_MARKER_RECORDED, null), "direct", "scope");
    // The TemporalChangeVersion upsert synthesized beside the marker inherits its annotations.
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES, null),
        "direct",
        "scope");
  }

  @Test
  public void sideEffectCommandsCarryTheAmbientScope() {
    List<HistoryEvent> events = sideEffectHistory();

    // EG-COMMANDS-24: Side Effect commands carry the ambient scope
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_MARKER_RECORDED, null), "direct", "scope");
  }

  @Test
  public void mutableSideEffectCommandsCarryTheAmbientScope() {
    List<HistoryEvent> events = mutableSideEffectHistory();

    // EG-COMMANDS-25: Mutable Side Effect commands carry the ambient scope
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_MARKER_RECORDED, null), "direct", "scope");
  }

  @Test
  public void upsertSearchAttributeCommandsCarryMarkers() {
    List<HistoryEvent> events = upsertSearchAttributesHistory();

    // EG-COMMANDS-21: Upsert search attribute commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES, null),
        "direct",
        "scope");
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////
  // SUPPORT WORKFLOWS AND ACTIVITIES
  ////////////////////////////////////////////////////////////////////////////////////////////////////

  @ActivityInterface
  public interface EventGroupActivities {
    @ActivityMethod
    String greet(String name);

    @ActivityMethod
    void block();

    @ActivityMethod
    void gate();
  }

  public static class Activities implements EventGroupActivities {
    private CountDownLatch started = new CountDownLatch(1);
    private CountDownLatch release = new CountDownLatch(1);
    // Released by the test after the workflow result is in, so a cancelled activity does not
    // complete into a workflow task that is still running.
    private CountDownLatch hold = new CountDownLatch(1);

    @Override
    public String greet(String name) {
      return "hi " + name;
    }

    @Override
    public void block() {
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
    public void gate() {
      started.countDown();
      try {
        if (!release.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("test did not release the gate");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw Activity.wrap(e);
      }
    }
  }

  @WorkflowInterface
  public interface CancelWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class CancelWorkflowImpl implements CancelWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () -> {
            CancellationScope longTimer =
                Workflow.newCancellationScope(
                    () ->
                        Workflow.newTimer(Duration.ofHours(1), timer(direct, "long-timer")).get());
            Promise<Void> running = Async.procedure(longTimer::run);
            Workflow.sleep(Duration.ofMillis(1), timer(null, "timeout"));
            longTimer.cancel();
            swallowCancellation(running);
          });
    }
  }

  private List<HistoryEvent> cancelHistory() {
    CancelWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(CancelWorkflow.class);
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
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(() -> Workflow.await(Duration.ofMillis(1), timer(direct, "await"), () -> false));
    }
  }

  private List<HistoryEvent> awaitHistory() {
    AwaitWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(AwaitWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ActivityWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ActivityWorkflowImpl implements ActivityWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.newActivityStub(EventGroupActivities.class, activity(direct, "activity"))
                  .greet("hi"));
    }
  }

  private List<HistoryEvent> activityHistory() {
    ActivityWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ActivityWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ActivityCancelWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ActivityCancelWorkflowImpl implements ActivityCancelWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () -> {
            CancellationScope activityScope =
                Workflow.newCancellationScope(
                    () ->
                        Workflow.newActivityStub(
                                EventGroupActivities.class,
                                activity(direct, "activity").toBuilder()
                                    .setCancellationType(ActivityCancellationType.TRY_CANCEL)
                                    .build())
                            .block());
            Promise<Void> running = Async.procedure(activityScope::run);
            // Forces a Workflow Task boundary so the schedule command reaches the server before
            // the cancellation does.
            Workflow.sleep(Duration.ofMillis(1));
            activityScope.cancel();
            swallowCancellation(running);
          });
    }
  }

  private List<HistoryEvent> activityCancelHistory() {
    ActivityCancelWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ActivityCancelWorkflow.class);
    try {
      workflow.execute();
    } finally {
      activities.hold.countDown();
    }
    return history(workflow);
  }

  @WorkflowInterface
  public interface ChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ChildWorkflowImpl implements ChildWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.newChildWorkflowStub(QuickChild.class, childOptions(direct, "child"))
                  .execute());
    }
  }

  @WorkflowInterface
  public interface QuickChild {
    @WorkflowMethod
    void execute();
  }

  public static class QuickChildImpl implements QuickChild {
    @Override
    public void execute() {}
  }

  private List<HistoryEvent> childHistory() {
    ChildWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(ChildWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ChildCancelWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ChildCancelWorkflowImpl implements ChildCancelWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () -> {
            CancellationScope childScope =
                Workflow.newCancellationScope(
                    () ->
                        Workflow.newChildWorkflowStub(
                                WaitingChild.class, childOptions(direct, "child"))
                            .execute());
            Promise<Void> running = Async.procedure(childScope::run);
            // Forces a Workflow Task boundary so the start command reaches the server before the
            // cancellation does.
            Workflow.sleep(Duration.ofMillis(1));
            childScope.cancel();
            swallowCancellation(running);
          });
    }
  }

  @WorkflowInterface
  public interface WaitingChild {
    @WorkflowMethod
    void execute();
  }

  public static class WaitingChildImpl implements WaitingChild {
    @Override
    public void execute() {
      Workflow.await(() -> false);
    }
  }

  private List<HistoryEvent> childCancelHistory() {
    ChildCancelWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ChildCancelWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface SignalTarget {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void go();
  }

  public static class SignalTargetImpl implements SignalTarget {
    private boolean go;

    @Override
    public void execute() {
      Workflow.await(() -> go);
    }

    @Override
    public void go() {
      go = true;
    }
  }

  @WorkflowInterface
  public interface SignalExternalWorkflow {
    @WorkflowMethod
    void execute(String workflowId);
  }

  public static class SignalExternalWorkflowImpl implements SignalExternalWorkflow {
    @Override
    public void execute(String workflowId) {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.newUntypedExternalWorkflowStub(workflowId)
                  .signal(
                      "go",
                      SignalExternalWorkflowOptions.newBuilder().addEventGroups(direct).build()));
    }
  }

  private List<HistoryEvent> signalExternalHistory() {
    SignalTarget target = testWorkflowRule.newWorkflowStubTimeoutOptions(SignalTarget.class);
    WorkflowClient.start(target::execute);

    SignalExternalWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SignalExternalWorkflow.class);
    workflow.execute(WorkflowStub.fromTyped(target).getExecution().getWorkflowId());
    return history(workflow);
  }

  @WorkflowInterface
  public interface ChildSignalWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ChildSignalWorkflowImpl implements ChildSignalWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      // The child starts outside the scope, so only the signal can supply the markers under test.
      SignalTarget child = Workflow.newChildWorkflowStub(SignalTarget.class);
      Promise<Void> done = Async.procedure(child::execute);
      Workflow.getWorkflowExecution(child).get();
      scope.run(
          () ->
              ChildWorkflowStub.fromTyped(child)
                  .signal(
                      "go",
                      SignalChildWorkflowOptions.newBuilder().addEventGroups(direct).build()));
      done.get();
    }
  }

  private List<HistoryEvent> childSignalHistory() {
    ChildSignalWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ChildSignalWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface CancelExternalWorkflow {
    @WorkflowMethod
    void execute(String workflowId);
  }

  public static class CancelExternalWorkflowImpl implements CancelExternalWorkflow {
    @Override
    public void execute(String workflowId) {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.newUntypedExternalWorkflowStub(workflowId)
                  .cancel(
                      CancelExternalWorkflowOptions.newBuilder().addEventGroups(direct).build()));
    }
  }

  private List<HistoryEvent> cancelExternalHistory() {
    WaitingChild target = testWorkflowRule.newWorkflowStubTimeoutOptions(WaitingChild.class);
    WorkflowClient.start(target::execute);

    CancelExternalWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(CancelExternalWorkflow.class);
    workflow.execute(WorkflowStub.fromTyped(target).getExecution().getWorkflowId());
    return history(workflow);
  }

  @WorkflowInterface
  public interface UpsertMemoWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class UpsertMemoWorkflowImpl implements UpsertMemoWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.upsertMemo(
                  Collections.singletonMap("some-key", "some-value"),
                  UpsertMemoOptions.newBuilder().addEventGroups(direct).build()));
    }
  }

  private List<HistoryEvent> upsertMemoHistory() {
    UpsertMemoWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UpsertMemoWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface UpsertSearchAttributesWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class UpsertSearchAttributesWorkflowImpl implements UpsertSearchAttributesWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.upsertTypedSearchAttributes(
                  UpsertSearchAttributesOptions.newBuilder().addEventGroups(direct).build(),
                  SearchAttributeKey.forKeyword("CustomKeywordField").valueSet("some-value")));
    }
  }

  @WorkflowInterface
  public interface VersionWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class VersionWorkflowImpl implements VersionWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.getVersion(
                  "my-change",
                  Workflow.DEFAULT_VERSION,
                  1,
                  GetVersionOptions.newBuilder().addEventGroups(direct).build()));
    }
  }

  private List<HistoryEvent> versionHistory() {
    VersionWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(VersionWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface SideEffectWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class SideEffectWorkflowImpl implements SideEffectWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.sideEffect(
                  Integer.class,
                  () -> 42,
                  SideEffectOptions.newBuilder().addEventGroups(direct).build()));
    }
  }

  private List<HistoryEvent> sideEffectHistory() {
    SideEffectWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SideEffectWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface MutableSideEffectWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class MutableSideEffectWorkflowImpl implements MutableSideEffectWorkflow {
    @Override
    public void execute() {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () ->
              Workflow.mutableSideEffect(
                  "my-id",
                  Integer.class,
                  (previous, next) -> !previous.equals(next),
                  () -> 42,
                  MutableSideEffectOptions.newBuilder().addEventGroups(direct).build()));
    }
  }

  private List<HistoryEvent> mutableSideEffectHistory() {
    MutableSideEffectWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(MutableSideEffectWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  private List<HistoryEvent> upsertSearchAttributesHistory() {
    UpsertSearchAttributesWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(UpsertSearchAttributesWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ScopeWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class ScopeWorkflowImpl implements ScopeWorkflow {
    private boolean release;
    private boolean go;

    @Override
    public void execute() {
      EventGroup aaa =
          Workflow.newEventGroup("aaa", EventGroupOptions.newBuilder().setLabel("aaa").build());
      EventGroup bbb = Workflow.newEventGroup("bbb");
      aaa.run(
          () -> {
            Workflow.sleep(Duration.ofMillis(1), timer(null, "a-before"));
            bbb.run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "a-b")));
            Workflow.sleep(Duration.ofMillis(1), timer(null, "a-after"));
          });
      Workflow.sleep(Duration.ofMillis(1), timer(null, "outside"));
      aaa.run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "reenter")));

      EventGroup scope = Workflow.newEventGroup("scope");
      scope.run(
          () -> {
            Promise<Void> branchA =
                Async.procedure(
                    () ->
                        Workflow.sleep(
                            Duration.ofMillis(1),
                            timer(Workflow.newEventGroup("branch-a"), "branch-a")));
            Promise<Void> branchB =
                Async.procedure(
                    () ->
                        Workflow.newEventGroup("inner")
                            .run(
                                () ->
                                    Workflow.sleep(Duration.ofMillis(1), timer(null, "branch-b"))));
            branchA.get();
            branchB.get();
          });

      Promise<Void> kept =
          Workflow.newEventGroup("kept")
              .run(
                  () ->
                      Async.procedure(
                          () -> {
                            Workflow.await(() -> release);
                            Workflow.sleep(Duration.ofMillis(1), timer(null, "kept-after-exit"));
                          }));
      release = true;
      kept.get();

      Promise<Void> outside =
          Async.procedure(
              () -> {
                Workflow.await(() -> go);
                Workflow.sleep(Duration.ofMillis(1), timer(null, "outside-thread"));
              });
      Workflow.newEventGroup("not-inherited").run(() -> go = true);
      outside.get();

      try {
        Workflow.newEventGroup("boom")
            .run(
                () -> {
                  Workflow.sleep(Duration.ofMillis(1), timer(null, "inside-throw"));
                  throw new RuntimeException("boom");
                });
      } catch (RuntimeException e) {
        Workflow.sleep(Duration.ofMillis(1), timer(null, "after-throw"));
      }

      EventGroup a1 = Workflow.newEventGroup("a1");
      EventGroup b1 =
          Workflow.newEventGroup("b", EventGroupOptions.newBuilder().setLabel("b1").build());
      EventGroup b2 =
          Workflow.newEventGroup("b", EventGroupOptions.newBuilder().setLabel("b2").build());
      Workflow.newTimer(
              Duration.ofMillis(1),
              TimerOptions.newBuilder()
                  .setSummary("same-instance-twice")
                  .addEventGroups(a1, a1)
                  .build())
          .get();
      a1.run(
          () ->
              a1.run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "nested-duplicate"))));
      a1.run(
          () ->
              Workflow.newTimer(
                      Duration.ofMillis(1),
                      TimerOptions.newBuilder()
                          .setSummary("scope-and-direct")
                          .addEventGroups(b1)
                          .build())
                  .get());
      Workflow.newTimer(
              Duration.ofMillis(1),
              TimerOptions.newBuilder().setSummary("same-id-direct").addEventGroups(b1, b2).build())
          .get();
      b1.run(
          () ->
              Workflow.newTimer(
                      Duration.ofMillis(1),
                      TimerOptions.newBuilder()
                          .setSummary("same-id-scope-and-direct")
                          .addEventGroups(b2)
                          .build())
                  .get());

      Workflow.newEventGroup("custom id / already")
          .run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "verbatim")));
      Workflow.newEventGroup("labeled", EventGroupOptions.newBuilder().setLabel("hello").build())
          .run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "labeled")));
      Workflow.newEventGroup("no-label")
          .run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "no-label")));
      Workflow.newEventGroup("other", EventGroupOptions.newBuilder().setLabel("hello").build())
          .run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "other-id")));
    }
  }

  private List<HistoryEvent> scopeHistory() {
    ScopeWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(ScopeWorkflow.class);
    workflow.execute();
    return history(workflow);
  }

  @WorkflowInterface
  public interface ImplicitWorkflow {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void onSignal(String value);

    @UpdateMethod
    String upd(String value);
  }

  public static class ImplicitWorkflowImpl implements ImplicitWorkflow {
    private String signal;
    private boolean updated;
    private boolean dynamicUpdated;

    @Override
    public void execute() {
      Workflow.registerListener(
          (DynamicUpdateHandler)
              (name, args) -> {
                Workflow.sleep(Duration.ofMillis(1), timer(null, "dynamic-update"));
                dynamicUpdated = true;
                return "ok";
              });
      Workflow.await(() -> signal != null);
      Workflow.sleep(Duration.ofMillis(1), timer(null, "main-after-signal"));
      Workflow.await(() -> updated);
      Workflow.sleep(Duration.ofMillis(1), timer(null, "main-after-update"));
      Workflow.await(() -> dynamicUpdated);
    }

    @Override
    public void onSignal(String value) {
      Workflow.newEventGroup("inside-signal")
          .run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "in-signal")));
      signal = value;
    }

    @Override
    public String upd(String value) {
      Workflow.newEventGroup("inside-update")
          .run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "in-update")));
      updated = true;
      return value;
    }
  }

  private List<HistoryEvent> implicitHistory() throws Exception {
    ImplicitWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ImplicitWorkflow.class);
    CompletableFuture<Void> done = WorkflowClient.execute(workflow::execute);
    workflow.onSignal("hello");
    workflow.upd("value");
    WorkflowStub.fromTyped(workflow).update("dynupd", String.class, "x");
    done.get(10, TimeUnit.SECONDS);
    return history(workflow);
  }

  @WorkflowInterface
  public interface RuntimeSignalWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class RuntimeSignalWorkflowImpl implements RuntimeSignalWorkflow {
    private int handled;

    @Override
    public void execute() {
      Workflow.newActivityStub(
              EventGroupActivities.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(20)).build())
          .gate();
      Workflow.newEventGroup("registration-scope")
          .run(
              () -> {
                // Listener methods are discovered on interfaces, so an anonymous Object with
                // annotations is ignored.
                Workflow.registerListener(new NamedSignal());
                Workflow.registerListener(
                    (DynamicSignalHandler)
                        (name, args) -> {
                          Workflow.sleep(Duration.ofMillis(1), timer(null, "dynamic-handler"));
                          handled++;
                        });
              });
      Workflow.await(() -> handled >= 2);
      Workflow.sleep(Duration.ofMillis(1), timer(null, "main-after"));
    }

    private class NamedSignal implements NamedSignalListener {
      @Override
      public void named(String value) {
        Workflow.newEventGroup("inside")
            .run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "named-handler")));
        handled++;
      }
    }
  }

  @WorkflowInterface
  public interface NamedSignalListener {
    @SignalMethod
    void named(String value);
  }

  @WorkflowInterface
  public interface RuntimeUpdateWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class RuntimeUpdateWorkflowImpl implements RuntimeUpdateWorkflow {
    private boolean done;

    @Override
    public void execute() {
      Workflow.newEventGroup("registration-scope")
          .run(() -> Workflow.registerListener(new NamedUpdate()));
      // Block after registration so the test can send the update once the handler exists.
      Workflow.newActivityStub(
              EventGroupActivities.class,
              ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(20)).build())
          .gate();
      Workflow.await(() -> done);
      Workflow.sleep(Duration.ofMillis(1), timer(null, "main-after"));
    }

    private class NamedUpdate implements NamedUpdateListener {
      @Override
      public void upd(String value) {
        Workflow.newEventGroup("inside")
            .run(() -> Workflow.sleep(Duration.ofMillis(1), timer(null, "in-runtime-update")));
        done = true;
      }
    }
  }

  @WorkflowInterface
  public interface NamedUpdateListener {
    @UpdateMethod
    void upd(String value);
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////
  // HISTORY HELPERS
  ////////////////////////////////////////////////////////////////////////////////////////////////////

  private List<HistoryEvent> history(Object workflow) {
    WorkflowExecutionId execution = executionOf(workflow);
    return testWorkflowRule
        .getWorkflowClient()
        .fetchHistory(execution.workflowId, execution.runId)
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

  private static long inboundEventId(HistoryEvent event) {
    long id = 0;
    int count = 0;
    for (EventGroupMarker marker : event.getEventGroupMarkersList()) {
      if (marker.hasInboundEvent()) {
        id = marker.getInboundEvent().getInboundEventId();
        count++;
      }
    }
    assertEquals(1, count);
    return id;
  }

  private static String inboundUpdateId(HistoryEvent event) {
    String id = null;
    int count = 0;
    for (EventGroupMarker marker : event.getEventGroupMarkersList()) {
      if (marker.hasInboundUpdate()) {
        id = marker.getInboundUpdate().getInboundUpdateId();
        count++;
      }
    }
    assertEquals(1, count);
    return id;
  }

  private static long signaledEventId(List<HistoryEvent> events, String signalName) {
    for (HistoryEvent event : events) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED
          && signalName.equals(
              event.getWorkflowExecutionSignaledEventAttributes().getSignalName())) {
        return event.getEventId();
      }
    }
    fail("no signal named " + signalName);
    return 0;
  }

  private static String acceptedUpdateId(List<HistoryEvent> events, String updateName) {
    for (HistoryEvent event : events) {
      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED
          && updateName.equals(
              event
                  .getWorkflowExecutionUpdateAcceptedEventAttributes()
                  .getAcceptedRequest()
                  .getInput()
                  .getName())) {
        return event.getWorkflowExecutionUpdateAcceptedEventAttributes().getProtocolInstanceId();
      }
    }
    fail("no accepted update named " + updateName);
    return null;
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

  private static HistoryEvent requireChild(List<HistoryEvent> events, String workflowIdSuffix) {
    for (HistoryEvent event : events) {
      if (event.getEventType() == EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED
          && event
              .getStartChildWorkflowExecutionInitiatedEventAttributes()
              .getWorkflowId()
              .endsWith(workflowIdSuffix)) {
        return event;
      }
    }
    fail("no child start for " + workflowIdSuffix);
    return null;
  }

  private static ChildWorkflowOptions childOptions(EventGroup direct, String suffix) {
    return ChildWorkflowOptions.newBuilder()
        .setTaskQueue(Workflow.getInfo().getTaskQueue())
        .setWorkflowId(Workflow.getInfo().getWorkflowId() + "-" + suffix)
        .setCancellationType(ChildWorkflowCancellationType.TRY_CANCEL)
        .addEventGroups(direct)
        .build();
  }

  private static ActivityOptions activity(EventGroup direct, String summary) {
    return ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(20))
        .setSummary(summary)
        .addEventGroups(direct)
        .build();
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
