package io.temporal.workflowstreams;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflowstreams.SubscribeTestWorkflows.SubscribeHostWorkflow;
import io.temporal.workflowstreams.SubscribeTestWorkflows.SubscribeHostWorkflowImpl;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowStreamCodecTest {
  private static final String CODEC_METADATA_KEY = "workflow-stream-codec-test";
  private static final TrackingCodec CODEC = new TrackingCodec();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      new CodecDataConverter(
                          DefaultDataConverter.newDefaultInstance(),
                          java.util.Collections.singletonList(CODEC)))
                  .build())
          .setWorkflowTypes(SubscribeHostWorkflowImpl.class)
          .build();

  @Test
  public void codecsApplyToTheEnvelopeButNotIndividualItems() {
    SubscribeHostWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(SubscribeHostWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, null);
    WorkflowStub stub =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub(execution.getWorkflowId());
    CODEC.encodeCalls.set(0);

    try (WorkflowStreamClient client =
        WorkflowStreamClient.newInstance(
            testWorkflowRule.getWorkflowClient(),
            execution.getWorkflowId(),
            WorkflowStreamClientOptions.newBuilder()
                .setBatchInterval(Duration.ofMillis(10))
                .build())) {
      client.topic("events").publish("value", true);
      client.flush();
      try (WorkflowStreamSubscription subscription =
          client.subscribe(SubscribeOptions.getDefaultInstance())) {
        WorkflowStreamItem item = subscription.next();
        Assert.assertFalse(item.getPayload().containsMetadata(CODEC_METADATA_KEY));
        Assert.assertEquals("value", client.decodeItem(item, String.class));
      }
    }
    Assert.assertTrue(CODEC.encodeCalls.get() > 0);
    stub.signal("finish");
    stub.getResult(Void.class);
  }

  private static final class TrackingCodec implements PayloadCodec {
    private final AtomicInteger encodeCalls = new AtomicInteger();

    @Override
    @Nonnull
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      encodeCalls.incrementAndGet();
      List<Payload> result = new ArrayList<>(payloads.size());
      for (Payload payload : payloads) {
        result.add(
            payload.toBuilder()
                .putMetadata(CODEC_METADATA_KEY, ByteString.copyFromUtf8("encoded"))
                .build());
      }
      return result;
    }

    @Override
    @Nonnull
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      List<Payload> result = new ArrayList<>(payloads.size());
      for (Payload payload : payloads) {
        result.add(payload.toBuilder().removeMetadata(CODEC_METADATA_KEY).build());
      }
      return result;
    }
  }
}
