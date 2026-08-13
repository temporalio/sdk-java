package io.temporal.workflowstreams;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.common.converter.ByteArrayPayloadConverter;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflowstreams.internal.PayloadWire;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowStreamTest {
  private static final DataConverter DC = DefaultDataConverter.STANDARD_INSTANCE;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              StreamHostWorkflowImpl.class,
              ByteOnlyPublishWorkflowImpl.class,
              InitHostWorkflowImpl.class)
          .build();

  private static PublishInput publishInput(String publisherId, long seq, String... topicValues) {
    List<PublishEntry> items = new ArrayList<>();
    for (int i = 0; i < topicValues.length; i += 2) {
      Payload payload = DC.toPayload(topicValues[i + 1]).get();
      items.add(new PublishEntry(topicValues[i], PayloadWire.encode(payload)));
    }
    return new PublishInput(items, publisherId, seq);
  }

  private WorkflowStub startHostWorkflow() {
    StreamHostWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(StreamHostWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, null);
    return testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(execution.getWorkflowId());
  }

  @Test
  public void testExternalPublishAndOffsetQuery() {
    WorkflowStub stub = startHostWorkflow();

    stub.signal(
        WorkflowStreamConstants.PUBLISH_SIGNAL_NAME,
        publishInput("pub1", 1, "events", "a", "events", "b"));
    // Poll first so the offset query observes the published items.
    stub.update(
        WorkflowStreamConstants.POLL_UPDATE_NAME,
        PollResult.class,
        new PollInput(Collections.emptyList(), 0));

    long offset = stub.query(WorkflowStreamConstants.OFFSET_QUERY_NAME, Long.class);
    Assert.assertEquals(2, offset);

    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testPublisherDedup() {
    WorkflowStub stub = startHostWorkflow();

    stub.signal(
        WorkflowStreamConstants.PUBLISH_SIGNAL_NAME, publishInput("pub1", 1, "events", "a"));
    // Same publisher + sequence: must be dropped.
    stub.signal(
        WorkflowStreamConstants.PUBLISH_SIGNAL_NAME, publishInput("pub1", 1, "events", "dup"));
    stub.signal(
        WorkflowStreamConstants.PUBLISH_SIGNAL_NAME, publishInput("pub1", 2, "events", "c"));

    PollResult result =
        stub.update(
            WorkflowStreamConstants.POLL_UPDATE_NAME,
            PollResult.class,
            new PollInput(Collections.emptyList(), 0));
    Assert.assertEquals("duplicate batch should be dropped", 2, result.items.size());
    Assert.assertEquals(2, result.nextOffset);
    Assert.assertEquals(
        "a",
        DC.fromPayload(PayloadWire.decode(result.items.get(0).data), String.class, String.class));
    Assert.assertEquals(
        "c",
        DC.fromPayload(PayloadWire.decode(result.items.get(1).data), String.class, String.class));

    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testPollReturnsItemsWithTopicFilter() {
    WorkflowStub stub = startHostWorkflow();

    stub.signal(
        WorkflowStreamConstants.PUBLISH_SIGNAL_NAME,
        publishInput("pub1", 1, "a", "1", "b", "2", "a", "3"));

    PollResult result =
        stub.update(
            WorkflowStreamConstants.POLL_UPDATE_NAME,
            PollResult.class,
            new PollInput(Collections.singletonList("a"), 0));

    // Only topic "a" items, with global offsets 0 and 2.
    Assert.assertEquals(2, result.items.size());
    Assert.assertEquals("a", result.items.get(0).topic);
    Assert.assertEquals(0, result.items.get(0).offset);
    Assert.assertEquals("a", result.items.get(1).topic);
    Assert.assertEquals(2, result.items.get(1).offset);
    Assert.assertEquals(3, result.nextOffset);
    Assert.assertFalse(result.moreReady);

    Payload payload = PayloadWire.decode(result.items.get(1).data);
    Assert.assertEquals("3", DC.fromPayload(payload, String.class, String.class));

    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testTruncate() {
    WorkflowStub stub = startHostWorkflow();

    stub.signal(
        WorkflowStreamConstants.PUBLISH_SIGNAL_NAME,
        publishInput("pub1", 1, "events", "a", "events", "b", "events", "c"));
    // Ensure the batch has been applied before truncating.
    stub.update(
        WorkflowStreamConstants.POLL_UPDATE_NAME,
        PollResult.class,
        new PollInput(Collections.emptyList(), 0));

    stub.update("truncate", Void.class, 2L);

    // Offset 0 means "from the beginning of whatever still exists".
    PollResult fromStart =
        stub.update(
            WorkflowStreamConstants.POLL_UPDATE_NAME,
            PollResult.class,
            new PollInput(Collections.emptyList(), 0));
    Assert.assertEquals(1, fromStart.items.size());
    Assert.assertEquals(2, fromStart.items.get(0).offset);

    // A poll positioned before the new base offset fails with TruncatedOffset.
    try {
      stub.update(
          WorkflowStreamConstants.POLL_UPDATE_NAME,
          PollResult.class,
          new PollInput(Collections.emptyList(), 1));
      Assert.fail("unreachable");
    } catch (WorkflowUpdateException e) {
      ApplicationFailure failure = (ApplicationFailure) e.getCause();
      Assert.assertEquals(WorkflowStreamConstants.ERROR_TYPE_TRUNCATED_OFFSET, failure.getType());
    }

    // Truncating past the end of the log fails with TruncateOutOfRange.
    try {
      stub.update("truncate", Void.class, 10L);
      Assert.fail("unreachable");
    } catch (WorkflowUpdateException e) {
      ApplicationFailure failure = (ApplicationFailure) e.getCause();
      Assert.assertEquals(
          WorkflowStreamConstants.ERROR_TYPE_TRUNCATE_OUT_OF_RANGE, failure.getType());
    }

    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testStreamConstructedInWorkflowInit() {
    InitHostWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(InitHostWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, null);
    WorkflowStub stub =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(execution.getWorkflowId());

    stub.signal(
        WorkflowStreamConstants.PUBLISH_SIGNAL_NAME, publishInput("pub1", 1, "events", "a"));
    PollResult result =
        stub.update(
            WorkflowStreamConstants.POLL_UPDATE_NAME,
            PollResult.class,
            new PollInput(Collections.emptyList(), 0));
    Assert.assertEquals(1, result.items.size());
    Assert.assertEquals(
        "a",
        DC.fromPayload(PayloadWire.decode(result.items.get(0).data), String.class, String.class));
    Assert.assertEquals(
        1L, (long) stub.query(WorkflowStreamConstants.OFFSET_QUERY_NAME, Long.class));

    stub.signal("finish");
    stub.getResult(Void.class);
  }

  @Test
  public void testWorkflowPublishUsesConfiguredConverters() {
    ByteOnlyPublishWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(ByteOnlyPublishWorkflow.class);
    Assert.assertTrue(
        "a string is unconvertible under the byte-array-only set, proving setPayloadConverters"
            + " drives conversion",
        workflow.execute());
  }

  @WorkflowInterface
  public interface StreamHostWorkflow {
    @WorkflowMethod
    void execute(WorkflowStreamState priorState);

    @SignalMethod
    void finish();

    @UpdateMethod
    void truncate(long upToOffset);
  }

  public static class StreamHostWorkflowImpl implements StreamHostWorkflow {
    private final WorkflowStream stream;
    private boolean finished;

    // @WorkflowInit so poll updates arriving before the workflow method runs (a real-server
    // race the in-process test service never exhibits) are accepted rather than rejected.
    @WorkflowInit
    public StreamHostWorkflowImpl(WorkflowStreamState priorState) {
      stream = WorkflowStream.newInstance(priorState);
    }

    @Override
    public void execute(WorkflowStreamState priorState) {
      Workflow.await(() -> finished);
    }

    @Override
    public void finish() {
      finished = true;
    }

    @Override
    public void truncate(long upToOffset) {
      stream.truncate(upToOffset);
    }
  }

  @WorkflowInterface
  public interface InitHostWorkflow {
    @WorkflowMethod
    void execute(WorkflowStreamState priorState);

    @SignalMethod
    void finish();
  }

  /** Hosts the stream from a {@code @WorkflowInit} constructor, the recommended pattern. */
  public static class InitHostWorkflowImpl implements InitHostWorkflow {
    private final WorkflowStream stream;
    private boolean finished;

    @WorkflowInit
    public InitHostWorkflowImpl(WorkflowStreamState priorState) {
      stream = WorkflowStream.newInstance(priorState);
    }

    @Override
    public void execute(WorkflowStreamState priorState) {
      Workflow.await(() -> finished);
    }

    @Override
    public void finish() {
      finished = true;
    }
  }

  @WorkflowInterface
  public interface ByteOnlyPublishWorkflow {
    @WorkflowMethod
    boolean execute();
  }

  /**
   * Restricts the stream to the byte-array converter and returns whether publishing a string failed
   * — it should, since that set has no converter for strings, whereas the default set's JSON
   * fallback would accept it. A byte[] must still publish cleanly.
   */
  public static class ByteOnlyPublishWorkflowImpl implements ByteOnlyPublishWorkflow {
    @Override
    public boolean execute() {
      WorkflowStream stream =
          WorkflowStream.newInstance(
              null,
              WorkflowStreamOptions.newBuilder()
                  .setPayloadConverters(new ByteArrayPayloadConverter())
                  .build());
      stream.topic("events").publish("hi".getBytes(StandardCharsets.UTF_8));
      try {
        stream.topic("events").publish("not-bytes");
        return false;
      } catch (DataConverterException e) {
        return true;
      }
    }
  }
}
