package io.temporal.workflowstreams;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SubscribeTest {
  private static final DataConverter DC = DefaultDataConverter.STANDARD_INSTANCE;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(SubscribeHostWorkflowImpl.class).build();

  private static final SubscribeOptions FAST_POLL =
      SubscribeOptions.newBuilder().setPollCooldown(Duration.ofMillis(50)).build();

  private WorkflowStub startHostWorkflow() {
    SubscribeHostWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SubscribeHostWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, null);
    return testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(execution.getWorkflowId());
  }

  private WorkflowStreamClient newStreamClient(WorkflowStub stub) {
    return WorkflowStreamClient.newInstance(
        testWorkflowRule.getWorkflowClient(),
        stub.getExecution().getWorkflowId(),
        WorkflowStreamClientOptions.newBuilder().setBatchInterval(Duration.ofMillis(100)).build());
  }

  private static String decode(WorkflowStreamItem item) {
    return DC.fromPayload(item.getPayload(), String.class, String.class);
  }

  @Test
  public void testSubscribeDeliversItemsAndAdvancesOffset() {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a", true);
      streamClient.flush();
      // A workflow-side publish lands on the same log.
      stub.signal("publishLocal", "evt", "b");

      try (WorkflowStreamSubscription subscription = streamClient.subscribe(FAST_POLL)) {
        Assert.assertTrue(subscription.hasNext());
        WorkflowStreamItem first = subscription.next();
        Assert.assertEquals("evt", first.getTopic());
        Assert.assertEquals("a", decode(first));
        Assert.assertEquals(0, first.getOffset());

        Assert.assertTrue(subscription.hasNext());
        WorkflowStreamItem second = subscription.next();
        Assert.assertEquals("b", decode(second));
        Assert.assertEquals(1, second.getOffset());
      }

      Assert.assertEquals(2, streamClient.getOffset());
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testTopicHandleSubscribeFilters() {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("a").publish("1");
      streamClient.topic("b").publish("2");
      streamClient.topic("a").publish("3");
      streamClient.flush();

      try (WorkflowStreamSubscription subscription = streamClient.topic("a").subscribe(0)) {
        Assert.assertEquals("1", decode(subscription.next()));
        WorkflowStreamItem second = subscription.next();
        Assert.assertEquals("3", decode(second));
        Assert.assertEquals(2, second.getOffset());
      }
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testSubscribeEndsCleanlyOnTerminal() {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a", true);
      streamClient.flush();

      try (WorkflowStreamSubscription subscription = streamClient.subscribe(FAST_POLL)) {
        Assert.assertEquals("a", decode(subscription.next()));

        // Complete the workflow, then keep polling: the subscription must end cleanly
        // rather than surface an error.
        stub.signal("finish");
        stub.getResult(Void.class);
        Assert.assertFalse(
            "terminal workflow should end the stream without surfacing an error",
            subscription.hasNext());
      }
    }
  }

  @Test
  public void testSubscribeFollowsContinueAsNew() {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a", true);
      streamClient.flush();

      try (WorkflowStreamSubscription subscription = streamClient.subscribe(FAST_POLL)) {
        WorkflowStreamItem first = subscription.next();
        Assert.assertEquals("a", decode(first));
        Assert.assertEquals(0, first.getOffset());

        // Roll the workflow over to a new run. The stream state (including item "a")
        // is carried across the continue-as-new boundary.
        stub.signal("rollover");
        streamClient.topic("evt").publish("b", true);
        streamClient.flush();

        // The subscription retries through the rollover (draining rejections, polls
        // lost to the closing run) and picks up on the successor run where the prior
        // log — and so the subscriber's offset — is preserved.
        WorkflowStreamItem second = subscription.next();
        Assert.assertEquals("b", decode(second));
        Assert.assertEquals(1, second.getOffset());
      }
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testSubscribeTruncationResetsOffset() {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a");
      streamClient.topic("evt").publish("b");
      streamClient.topic("evt").publish("c");
      streamClient.flush();
      // Confirm the batch has been applied before truncating.
      try (WorkflowStreamSubscription warmup = streamClient.subscribe(FAST_POLL)) {
        warmup.next();
      }

      stub.update("truncate", Void.class, 2L);

      // A subscription positioned before the new base offset restarts from the
      // beginning of whatever still exists instead of failing.
      SubscribeOptions options =
          SubscribeOptions.newBuilder()
              .setFromOffset(1)
              .setPollCooldown(Duration.ofMillis(50))
              .build();
      try (WorkflowStreamSubscription subscription = streamClient.subscribe(options)) {
        WorkflowStreamItem item = subscription.next();
        Assert.assertEquals("c", decode(item));
        Assert.assertEquals(2, item.getOffset());
      }

      Assert.assertEquals(3, streamClient.getOffset());
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testCloseStopsIteration() {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      WorkflowStreamSubscription subscription = streamClient.subscribe(FAST_POLL);
      subscription.close();
      Assert.assertFalse(subscription.hasNext());
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @WorkflowInterface
  public interface SubscribeHostWorkflow {
    @WorkflowMethod
    void execute(WorkflowStreamState priorState);

    @SignalMethod
    void finish();

    @SignalMethod
    void rollover();

    @SignalMethod
    void publishLocal(String topic, String value);

    @UpdateMethod
    void truncate(long upToOffset);
  }

  public static class SubscribeHostWorkflowImpl implements SubscribeHostWorkflow {
    private WorkflowStream stream;
    private boolean finished;
    private boolean rollover;

    @Override
    public void execute(WorkflowStreamState priorState) {
      stream = WorkflowStream.newInstance(priorState);
      Workflow.await(() -> finished || rollover);
      if (rollover) {
        stream.continueAsNew(state -> new Object[] {state});
      }
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public void rollover() {
      rollover = true;
    }

    @Override
    public void publishLocal(String topic, String value) {
      stream.topic(topic).publish(value);
    }

    @Override
    public void truncate(long upToOffset) {
      stream.truncate(upToOffset);
    }
  }
}
