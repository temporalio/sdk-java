package io.temporal.workflow.nexus;

import static io.temporal.internal.common.WorkflowExecutionUtils.getEventOfType;
import static org.junit.Assume.assumeTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityHandle;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.nexus.Nexus;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies link propagation with activities when a synchronous Nexus operation handler starts more
 * than one activity via a raw {@link ActivityClient} obtained from {@link
 * Nexus#getOperationContext()}.
 *
 * <ul>
 *   <li>Forward direction: each activity's own record links back to the caller's {@code
 *       NexusOperationScheduled} event.
 *   <li>Backward direction: both activities' completions land as response links on the caller's
 *       single {@code NexusOperationCompleted} event.
 * </ul>
 *
 * <p>Requires a real server; the in-process test server does not implement {@code
 * StartActivityExecution} (see {@link AsyncActivityOperationTest}, which has the same gate).
 */
public class ActivityOperationLinkingTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setActivityImplementations(new TestActivityImpl())
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @BeforeClass
  public static void requireExternalService() {
    assumeTrue(
        "standalone-activity Nexus links require a real server",
        SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void testTwoBypassActivitiesBothLinkToOperation() {
    String input = "world-" + UUID.randomUUID();
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    String result = workflowStub.execute(input);
    Assert.assertEquals("hello " + input + "-a|hello " + input + "-b", result);

    String callerWorkflowId = WorkflowStub.fromTyped(workflowStub).getExecution().getWorkflowId();
    History callerHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(callerWorkflowId).getHistory();

    // Backward direction: both activities' completions must land on the caller's single
    // NexusOperationCompleted event as response links, not just the guarded/first one.
    HistoryEvent completed =
        getEventOfType(callerHistory, EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED);
    Assert.assertNotNull("expected a NexusOperationCompleted event", completed);
    Assert.assertEquals(
        "expected one response link per bypass-path activity", 2, completed.getLinksCount());
    Set<String> linkedActivityIds = new HashSet<>();
    for (int i = 0; i < completed.getLinksCount(); i++) {
      Link.Activity activityLink = completed.getLinks(i).getActivity();
      Assert.assertNotNull("expected an Activity-typed response link", activityLink);
      linkedActivityIds.add(activityLink.getActivityId());
    }
    Assert.assertTrue(linkedActivityIds.contains("act-" + input + "-a"));
    Assert.assertTrue(linkedActivityIds.contains("act-" + input + "-b"));

    // Forward direction: each activity's own record links back to the caller's
    // NexusOperationScheduled event, not just the guarded/first one.
    ActivityClient activityClient =
        ActivityClient.newInstance(
            testWorkflowRule.getWorkflowServiceStubs(),
            ActivityClientOptions.newBuilder().setNamespace(SDKTestWorkflowRule.NAMESPACE).build());
    for (String suffix : new String[] {"a", "b"}) {
      String activityId = "act-" + input + "-" + suffix;
      ActivityExecutionDescription description =
          activityClient.getHandle(activityId, null).describe();
      Assert.assertTrue(
          "expected at least one link on activity " + activityId,
          description.getRawInfo().getLinksCount() >= 1);
      Link.WorkflowEvent forwardLink = description.getRawInfo().getLinks(0).getWorkflowEvent();
      Assert.assertNotNull(
          "expected a WorkflowEvent-typed forward link on activity " + activityId, forwardLink);
      Assert.assertEquals(callerWorkflowId, forwardLink.getWorkflowId());
      Assert.assertEquals(
          EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED, forwardLink.getEventRef().getEventType());
    }
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow1 {
    @Override
    public String execute(String input) {
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setOperationOptions(
                  NexusOperationOptions.newBuilder()
                      .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                      .build())
              .build();
      TestNexusServices.TestNexusService1 stub =
          Workflow.newNexusServiceStub(TestNexusServices.TestNexusService1.class, serviceOptions);
      return stub.operation(input);
    }
  }

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String process(String input);
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public String process(String input) {
      return "hello " + input;
    }
  }

  /**
   * Starts two activities inline via a raw {@link ActivityClient} obtained from {@link
   * Nexus#getOperationContext()} instead of {@code TemporalOperationHandler}'s single-guarded-call
   * {@code TemporalNexusClient} -- the only way to start more than one activity synchronously in
   * one Nexus operation invocation.
   */
  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (ctx, details, input) -> {
            ActivityClient activityClient =
                ActivityClient.newInstance(
                    Nexus.getOperationContext().getWorkflowClient().getWorkflowServiceStubs(),
                    ActivityClientOptions.newBuilder()
                        .setNamespace(Nexus.getOperationContext().getInfo().getNamespace())
                        .build());
            String taskQueue = Nexus.getOperationContext().getInfo().getTaskQueue();

            ActivityHandle<String> first =
                activityClient.start(
                    TestActivity.class,
                    TestActivity::process,
                    StartActivityOptions.newBuilder()
                        .setId("act-" + input + "-a")
                        .setTaskQueue(taskQueue)
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build(),
                    input + "-a");
            ActivityHandle<String> second =
                activityClient.start(
                    TestActivity.class,
                    TestActivity::process,
                    StartActivityOptions.newBuilder()
                        .setId("act-" + input + "-b")
                        .setTaskQueue(taskQueue)
                        .setStartToCloseTimeout(Duration.ofSeconds(10))
                        .build(),
                    input + "-b");
            return first.getResult() + "|" + second.getResult();
          });
    }
  }
}
