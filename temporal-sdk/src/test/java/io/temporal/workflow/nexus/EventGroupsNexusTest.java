package io.temporal.workflow.nexus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowHandle;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.EventGroup;
import io.temporal.workflow.NexusOperationCancellationType;
import io.temporal.workflow.NexusOperationHandle;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote("Cloud CI does not provision the Nexus endpoint required by this test.")
@Category(RequiresCloudProvisioning.class)
public class EventGroupsNexusTest extends BaseNexusTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              NexusCommandWorkflowImpl.class,
              NexusOperationWorkflowImpl.class,
              NexusOperationSleep5sWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void nexusOperationCommandsCarryMarkers() {
    List<HistoryEvent> events = nexusCommandHistory();

    // EG-COMMANDS-05: Nexus Operation commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED, "nexus-operation"),
        "direct",
        "scope");
  }

  @Test
  public void nexusOperationCancellationCommandsCarryTheNexusOperationsMarkers() {
    List<HistoryEvent> events = nexusCommandHistory();

    // EG-COMMANDS-05-CANCEL: Nexus Operation Cancellation commands carry the Nexus Operation's
    // markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED, "nexus-operation-sleep-5s"),
        "direct",
        "scope");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED, null),
        "direct",
        "scope");
  }

  private List<HistoryEvent> nexusCommandHistory() {
    NexusCommandWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(NexusCommandWorkflow.class);
    workflow.execute();
    return testWorkflowRule
        .getWorkflowClient()
        .fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId())
        .getEvents();
  }

  @WorkflowInterface
  public interface NexusCommandWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class NexusCommandWorkflowImpl implements NexusCommandWorkflow {
    @Override
    public void execute() {
      EventGroup direct = Workflow.newEventGroup("direct");
      EventGroup scope = Workflow.newEventGroup("scope");
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(
                  NexusOperationOptions.newBuilder()
                      .addEventGroups(direct)
                      .setCancellationType(NexusOperationCancellationType.WAIT_REQUESTED)
                      .build())
              .build();
      TestNexusService service =
          Workflow.newNexusServiceStub(TestNexusService.class, serviceOptions);
      scope.run(
          () -> {
            Workflow.startNexusOperation(service::nexusOperation).getResult().get();

            try {
              Workflow.newCancellationScope(
                      () -> {
                        NexusOperationHandle<Void> handle =
                            Workflow.startNexusOperation(service::nexusOperationSleep5s);
                        // Cancelling before the operation is scheduled records no
                        // cancel-requested event.
                        handle.getExecution().get();
                        CancellationScope.current().cancel();
                        handle.getResult().get();
                      })
                  .run();
            } catch (NexusOperationFailure expected) {
              if (!(expected.getCause() instanceof CanceledFailure)) {
                throw expected;
              }
            }
          });
    }
  }

  @WorkflowInterface
  public interface NexusOperationWorkflow {
    @WorkflowMethod
    Void execute();
  }

  public static class NexusOperationWorkflowImpl implements NexusOperationWorkflow {
    @Override
    public Void execute() {
      return null;
    }
  }

  @WorkflowInterface
  public interface NexusOperationSleep5sWorkflow {
    @WorkflowMethod
    Void execute();
  }

  public static class NexusOperationSleep5sWorkflowImpl implements NexusOperationSleep5sWorkflow {
    @Override
    public Void execute() {
      try {
        // Stays open so the caller can cancel the operation before it completes.
        Workflow.await(() -> false);
      } catch (CanceledFailure ignored) {
        // Completing after cancellation lets the caller's operation finish.
      }
      return null;
    }
  }

  @Service
  public interface TestNexusService {
    @Operation(name = "nexus-operation")
    Void nexusOperation();

    @Operation(name = "nexus-operation-sleep-5s")
    Void nexusOperationSleep5s();
  }

  @ServiceImpl(service = TestNexusService.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Void, Void> nexusOperation() {
      return WorkflowRunOperation.fromWorkflowHandle(
          (context, details, input) ->
              WorkflowHandle.fromWorkflowMethod(
                  Nexus.getOperationContext()
                          .getWorkflowClient()
                          .newWorkflowStub(
                              NexusOperationWorkflow.class, workflowOptions(details.getRequestId()))
                      ::execute));
    }

    @OperationImpl
    public OperationHandler<Void, Void> nexusOperationSleep5s() {
      return WorkflowRunOperation.fromWorkflowHandle(
          (context, details, input) ->
              WorkflowHandle.fromWorkflowMethod(
                  Nexus.getOperationContext()
                          .getWorkflowClient()
                          .newWorkflowStub(
                              NexusOperationSleep5sWorkflow.class,
                              workflowOptions(details.getRequestId()))
                      ::execute));
    }

    private WorkflowOptions workflowOptions(String requestId) {
      return WorkflowOptions.newBuilder().setWorkflowId("event-groups-nexus-" + requestId).build();
    }
  }

  private static HistoryEvent require(List<HistoryEvent> events, EventType type, String operation) {
    for (HistoryEvent event : events) {
      if (event.getEventType() != type) {
        continue;
      }
      if (operation != null
          && !operation.equals(event.getNexusOperationScheduledEventAttributes().getOperation())) {
        continue;
      }
      return event;
    }
    fail("no " + type + (operation == null ? "" : " for " + operation));
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
}
