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
          .setWorkflowTypes(EventGroupsNexusWorkflowImpl.class, OperationWorkflow.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  @Test
  public void nexusOperationCommandsCarryMarkers() {
    List<HistoryEvent> events = history(false);

    // EG-COMMANDS-05: Nexus Operation commands carry markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED), "direct", "scope");
  }

  @Test
  public void nexusOperationCancellationCommandsCarryTheNexusOperationsMarkers() {
    List<HistoryEvent> events = history(true);

    // EG-COMMANDS-05-CANCEL: Nexus Operation Cancellation commands carry the Nexus Operation's
    // markers
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED), "direct", "scope");
    assertLabelGroups(
        require(events, EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED), "direct", "scope");
  }

  private List<HistoryEvent> history(boolean cancel) {
    EventGroupsNexusWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(EventGroupsNexusWorkflow.class);
    workflow.execute(cancel);
    return testWorkflowRule
        .getWorkflowClient()
        .fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId())
        .getEvents();
  }

  private static HistoryEvent require(List<HistoryEvent> events, EventType type) {
    for (HistoryEvent event : events) {
      if (event.getEventType() == type) {
        return event;
      }
    }
    fail("no " + type);
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

  @WorkflowInterface
  public interface EventGroupsNexusWorkflow {
    @WorkflowMethod
    void execute(boolean cancel);
  }

  @WorkflowInterface
  public interface OperationHandlerWorkflow {
    @WorkflowMethod
    Void execute(Long finishDelay);
  }

  public static class OperationWorkflow implements OperationHandlerWorkflow {
    @Override
    public Void execute(Long finishDelay) {
      // A zero delay lets the start-only test finish. Anything else waits for the caller to cancel.
      if (finishDelay != null && finishDelay == 0L) {
        return null;
      }
      try {
        Workflow.await(() -> false);
      } catch (CanceledFailure ignored) {
        // Completing after cancellation lets the caller's operation finish.
      }
      return null;
    }
  }

  public static class EventGroupsNexusWorkflowImpl implements EventGroupsNexusWorkflow {
    @Override
    public void execute(boolean cancel) {
      EventGroup scope = Workflow.newEventGroup("scope");
      EventGroup direct = Workflow.newEventGroup("direct");
      scope.run(
          () -> {
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
            if (!cancel) {
              Workflow.startNexusOperation(service::operation, 0L).getResult().get();
              return;
            }
            try {
              Workflow.newCancellationScope(
                      () -> {
                        NexusOperationHandle<Void> handle =
                            Workflow.startNexusOperation(service::operation, 1L);
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

  @Service
  public interface TestNexusService {
    @Operation
    Void operation(Long finishDelay);
  }

  @ServiceImpl(service = TestNexusService.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Long, Void> operation() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          OperationHandlerWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId("event-groups-nexus-" + details.getRequestId())
                              .build())
                  ::execute);
    }
  }
}
