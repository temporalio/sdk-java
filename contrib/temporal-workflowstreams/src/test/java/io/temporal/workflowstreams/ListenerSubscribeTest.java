package io.temporal.workflowstreams;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflowstreams.SubscribeTestWorkflows.SubscribeHostWorkflow;
import io.temporal.workflowstreams.SubscribeTestWorkflows.SubscribeHostWorkflowImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ListenerSubscribeTest {
  private static final DataConverter DC = DefaultDataConverter.STANDARD_INSTANCE;
  private static final long TIMEOUT_MS = 15_000;

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

  /**
   * Records callbacks. {@code gates} feeds onNext return stages in delivery order; once drained,
   * onNext returns null (proceed immediately).
   */
  private static class RecordingListener implements WorkflowStreamListener {
    final List<WorkflowStreamItem> items = new CopyOnWriteArrayList<>();
    final Queue<CompletionStage<Void>> gates = new ConcurrentLinkedQueue<>();
    final CountDownLatch completed = new CountDownLatch(1);
    final CountDownLatch failed = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public CompletionStage<Void> onNext(WorkflowStreamItem item) {
      items.add(item);
      return gates.poll();
    }

    @Override
    public void onError(Throwable failure) {
      error.set(failure);
      failed.countDown();
    }

    @Override
    public void onCompleted() {
      completed.countDown();
    }
  }

  private static void awaitItems(RecordingListener listener, int count) {
    long deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (listener.items.size() < count) {
      if (System.currentTimeMillis() > deadline) {
        Assert.fail("timed out waiting for " + count + " items, got " + listener.items.size());
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        Assert.fail("interrupted");
      }
    }
  }

  private static void await(CountDownLatch latch, String what) throws InterruptedException {
    Assert.assertTrue(
        "timed out waiting for " + what, latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
  }

  @Test
  public void testListenerDeliversItemsAndAdvancesOffset() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a", true);
      streamClient.flush();
      // A workflow-side publish lands on the same log.
      stub.signal("publishLocal", "evt", "b");

      RecordingListener listener = new RecordingListener();
      try (WorkflowStreamSubscriptionHandle handle = streamClient.subscribe(FAST_POLL, listener)) {
        awaitItems(listener, 2);
        WorkflowStreamItem first = listener.items.get(0);
        Assert.assertEquals("evt", first.getTopic());
        Assert.assertEquals("a", decode(first));
        Assert.assertEquals(0, first.getOffset());

        WorkflowStreamItem second = listener.items.get(1);
        Assert.assertEquals("b", decode(second));
        Assert.assertEquals(1, second.getOffset());
      }

      Assert.assertEquals(2, streamClient.getOffset());
      Assert.assertNull(listener.error.get());
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testTopicHandleListenerSubscribeFilters() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("a").publish("1");
      streamClient.topic("b").publish("2");
      streamClient.topic("a").publish("3");
      streamClient.flush();

      RecordingListener listener = new RecordingListener();
      try (WorkflowStreamSubscriptionHandle handle =
          streamClient.topic("a").subscribe(0, listener)) {
        awaitItems(listener, 2);
        Assert.assertEquals("1", decode(listener.items.get(0)));
        WorkflowStreamItem second = listener.items.get(1);
        Assert.assertEquals("3", decode(second));
        Assert.assertEquals(2, second.getOffset());
      }
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testTerminalCallsOnCompleted() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a", true);
      streamClient.flush();

      RecordingListener listener = new RecordingListener();
      WorkflowStreamSubscriptionHandle handle = streamClient.subscribe(FAST_POLL, listener);
      awaitItems(listener, 1);

      // Complete the workflow, then keep polling: the subscription must end cleanly
      // with onCompleted rather than surface an error.
      stub.signal("finish");
      stub.getResult(Void.class);

      await(listener.completed, "onCompleted");
      Assert.assertNull(listener.error.get());
      handle.getDoneFuture().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }
  }

  @Test
  public void testListenerFollowsContinueAsNew() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a", true);
      streamClient.flush();

      RecordingListener listener = new RecordingListener();
      try (WorkflowStreamSubscriptionHandle handle = streamClient.subscribe(FAST_POLL, listener)) {
        awaitItems(listener, 1);
        Assert.assertEquals("a", decode(listener.items.get(0)));

        // Roll the workflow over to a new run. The stream state (including item "a")
        // is carried across the continue-as-new boundary.
        stub.signal("rollover");
        streamClient.topic("evt").publish("b", true);
        streamClient.flush();

        // The subscription retries through the rollover and picks up on the successor
        // run where the prior log — and so the subscriber's offset — is preserved.
        awaitItems(listener, 2);
        WorkflowStreamItem second = listener.items.get(1);
        Assert.assertEquals("b", decode(second));
        Assert.assertEquals(1, second.getOffset());
        Assert.assertNull(listener.error.get());
      }
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testListenerTruncationResetsOffset() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a");
      streamClient.topic("evt").publish("b");
      streamClient.topic("evt").publish("c");
      streamClient.flush();
      // Confirm the batch has been applied before truncating.
      long deadline = System.currentTimeMillis() + TIMEOUT_MS;
      while (streamClient.getOffset() < 3 && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
      }
      Assert.assertEquals(3, streamClient.getOffset());

      stub.update("truncate", Void.class, 2L);

      // A subscription positioned before the new base offset restarts from the
      // beginning of whatever still exists instead of failing.
      SubscribeOptions options =
          SubscribeOptions.newBuilder()
              .setFromOffset(1)
              .setPollCooldown(Duration.ofMillis(50))
              .build();
      RecordingListener listener = new RecordingListener();
      try (WorkflowStreamSubscriptionHandle handle = streamClient.subscribe(options, listener)) {
        awaitItems(listener, 1);
        WorkflowStreamItem item = listener.items.get(0);
        Assert.assertEquals("c", decode(item));
        Assert.assertEquals(2, item.getOffset());
        Assert.assertNull(listener.error.get());
      }
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testCloseStopsDeliveryWithoutOnCompleted() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a", true);
      streamClient.flush();

      RecordingListener listener = new RecordingListener();
      WorkflowStreamSubscriptionHandle handle = streamClient.subscribe(FAST_POLL, listener);
      awaitItems(listener, 1);

      handle.close();
      handle.getDoneFuture().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

      streamClient.topic("evt").publish("b", true);
      streamClient.flush();
      Thread.sleep(300);
      Assert.assertEquals("no items may arrive after close", 1, listener.items.size());
      Assert.assertEquals(
          "user-initiated close must not call onCompleted", 1, listener.completed.getCount());
      Assert.assertNull(listener.error.get());
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testPendingOnNextStageDefersDelivery() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      RecordingListener listener = new RecordingListener();
      CompletableFuture<Void> gate = new CompletableFuture<>();
      listener.gates.add(gate);

      streamClient.topic("evt").publish("a");
      streamClient.topic("evt").publish("b");
      streamClient.flush();

      try (WorkflowStreamSubscriptionHandle handle = streamClient.subscribe(FAST_POLL, listener)) {
        awaitItems(listener, 1);
        // The first item's stage is pending, so the second must not be delivered yet.
        Thread.sleep(300);
        Assert.assertEquals(
            "delivery must pause while an onNext stage is pending", 1, listener.items.size());

        gate.complete(null);
        awaitItems(listener, 2);
        Assert.assertEquals("b", decode(listener.items.get(1)));
        Assert.assertNull(listener.error.get());
      }
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testOnNextThrowingStopsSubscription() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      RuntimeException boom = new RuntimeException("boom");
      RecordingListener listener =
          new RecordingListener() {
            @Override
            public CompletionStage<Void> onNext(WorkflowStreamItem item) {
              super.onNext(item);
              throw boom;
            }
          };

      streamClient.topic("evt").publish("a");
      streamClient.topic("evt").publish("b");
      streamClient.flush();

      streamClient.subscribe(FAST_POLL, listener);
      await(listener.failed, "onError");
      Assert.assertSame(boom, listener.error.get());
      Thread.sleep(300);
      Assert.assertEquals("no items may follow a failed onNext", 1, listener.items.size());
      Assert.assertEquals(1, listener.completed.getCount());
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testFailedOnNextStageStopsSubscription() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      RuntimeException boom = new RuntimeException("stage failed");
      RecordingListener listener = new RecordingListener();
      CompletableFuture<Void> gate = new CompletableFuture<>();
      listener.gates.add(gate);

      streamClient.topic("evt").publish("a");
      streamClient.topic("evt").publish("b");
      streamClient.flush();

      WorkflowStreamSubscriptionHandle handle = streamClient.subscribe(FAST_POLL, listener);
      awaitItems(listener, 1);
      gate.completeExceptionally(boom);

      await(listener.failed, "onError");
      Assert.assertSame(boom, listener.error.get());
      try {
        handle.getDoneFuture().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Assert.fail("done future must complete exceptionally");
      } catch (ExecutionException e) {
        Assert.assertSame(boom, e.getCause());
      }
      Thread.sleep(300);
      Assert.assertEquals("no items may follow a failed stage", 1, listener.items.size());
    }
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testManySubscriptionsShareSmallPool() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    // The default pool has 2 threads; 6 concurrent subscriptions only make progress if no
    // thread is held while a poll is blocked on the server.
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      List<RecordingListener> listeners = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        RecordingListener listener = new RecordingListener();
        listeners.add(listener);
        streamClient.subscribe(FAST_POLL, listener);
      }

      streamClient.topic("evt").publish("a", true);
      streamClient.flush();
      for (RecordingListener listener : listeners) {
        awaitItems(listener, 1);
      }

      stub.signal("finish");
      stub.getResult(Void.class);
      for (RecordingListener listener : listeners) {
        await(listener.completed, "onCompleted");
        Assert.assertNull(listener.error.get());
      }
    }
  }

  @Test
  public void testClientCloseStopsSubscriptions() throws Exception {
    WorkflowStub stub = startHostWorkflow();
    RecordingListener listener = new RecordingListener();
    WorkflowStreamSubscriptionHandle handle;
    try (WorkflowStreamClient streamClient = newStreamClient(stub)) {
      streamClient.topic("evt").publish("a", true);
      streamClient.flush();
      handle = streamClient.subscribe(FAST_POLL, listener);
      awaitItems(listener, 1);
    }
    // Closing the client stops the subscription cleanly.
    handle.getDoneFuture().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    Assert.assertNull(listener.error.get());
    stub.signal("finish");
    stub.getResult(Void.class);
  }
}
