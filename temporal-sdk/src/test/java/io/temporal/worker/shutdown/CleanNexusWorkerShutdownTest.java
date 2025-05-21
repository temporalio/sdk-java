package io.temporal.worker.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.PollerBehavior;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestNexusServices;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CleanNexusWorkerShutdownTest {

  private static final String COMPLETED = "Completed";
  private static final String INTERRUPTED = "Interrupted";
  private CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final CountDownLatch shutdownNowLatch = new CountDownLatch(1);
  private final TestNexusServiceImpl nexusServiceImpl =
      new TestNexusServiceImpl(shutdownLatch, shutdownNowLatch);

  @Parameterized.Parameters
  public static Collection<PollerBehavior> data() {
    return Arrays.asList(
        new PollerBehavior[] {
          new PollerBehaviorSimpleMaximum(10), new PollerBehaviorAutoscaling(1, 10, 5),
        });
  }

  @Rule public SDKTestWorkflowRule testWorkflowRule;

  public CleanNexusWorkerShutdownTest(PollerBehavior pollerBehaviorAutoscaling) {
    this.testWorkflowRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowTypes(TestWorkflowImpl.class)
            .setWorkerOptions(
                WorkerOptions.newBuilder()
                    .setLocalActivityWorkerOnly(true)
                    .setNexusTaskPollersBehaviour(pollerBehaviorAutoscaling)
                    .build())
            .setNexusServiceImplementation(nexusServiceImpl)
            .build();
  }

  @Test(timeout = 20000)
  public void testShutdown() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, null);
    shutdownLatch.await();
    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED) {
        found = true;
        Payload ar = e.getNexusOperationCompletedEventAttributes().getResult();
        String r =
            DefaultDataConverter.STANDARD_INSTANCE.fromPayload(ar, String.class, String.class);
        assertEquals(COMPLETED, r);
      }
    }
    assertTrue("Contains NexusOperationCompleted", found);
  }

  @Test(timeout = 20000)
  public void testShutdownNow() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, "now");
    shutdownNowLatch.await();
    testWorkflowRule.getTestEnvironment().shutdownNow();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED) {
        found = true;
        Payload ar = e.getNexusOperationCompletedEventAttributes().getResult();
        String r =
            DefaultDataConverter.STANDARD_INSTANCE.fromPayload(ar, String.class, String.class);
        assertEquals(INTERRUPTED, r);
      }
    }
    assertTrue("Contains NexusOperationCompleted", found);
  }

  public static class TestWorkflowImpl implements TestWorkflow1 {

    private final TestNexusServices.TestNexusService1 service =
        Workflow.newNexusServiceStub(
            TestNexusServices.TestNexusService1.class,
            NexusServiceOptions.newBuilder()
                .setOperationOptions(
                    NexusOperationOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                        .build())
                .build());

    @Override
    public String execute(String now) {
      return service.operation(now);
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    private final CountDownLatch shutdownLatch;
    private final CountDownLatch shutdownNowLatch;

    public TestNexusServiceImpl(CountDownLatch shutdownLatch, CountDownLatch shutdownNowLatch) {
      this.shutdownLatch = shutdownLatch;
      this.shutdownNowLatch = shutdownNowLatch;
    }

    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync(
          (ctx, details, now) -> {
            if (now == null) {
              shutdownLatch.countDown();
            } else {
              shutdownNowLatch.countDown();
            }
            try {
              Thread.sleep(3000);
            } catch (InterruptedException e) {
              // We ignore the interrupted exception here to let the operation complete and return
              // the
              // result. Otherwise, the result is not reported:
              return INTERRUPTED;
            }
            return COMPLETED;
          });
    }
  }
}
