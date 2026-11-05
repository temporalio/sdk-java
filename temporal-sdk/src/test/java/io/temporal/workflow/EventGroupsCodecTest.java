package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.sdk.v1.EventGroupMarker;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;

public class EventGroupsCodecTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      new CodecDataConverter(
                          DefaultDataConverter.STANDARD_INSTANCE,
                          Collections.singletonList(
                              new CodecDataConverterTest.PrefixPayloadCodec())))
                  .build())
          .setWorkflowTypes(LabeledWorkflowImpl.class)
          .build();

  @Test
  public void labelIsCodecEncodedAndIdIsNot() {
    LabeledWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(LabeledWorkflow.class);
    workflow.execute();
    HistoryEvent timer = null;
    for (HistoryEvent event :
        testWorkflowRule
            .getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId())
            .getEvents()) {
      if (event.getEventType() == EventType.EVENT_TYPE_TIMER_STARTED) {
        timer = event;
        break;
      }
    }
    EventGroupMarker marker = timer.getEventGroupMarkers(0);
    // EG-LABEL-PAYLOAD-21: Label ID is not codec-encoded
    assertEquals("group-id", marker.getLabel().getId());
    assertFalse(marker.getLabel().getId().contains("ENCODED"));
    Payload label = marker.getLabel().getLabel();
    // EG-LABEL-PAYLOAD-20: Label Payload is codec-encoded
    assertTrue(label.getData().startsWith(CodecDataConverterTest.PrefixPayloadCodec.PREFIX));
    assertEquals(
        "hello",
        testWorkflowRule
            .getWorkflowClient()
            .getOptions()
            .getDataConverter()
            .fromPayload(label, String.class, String.class));
  }

  @WorkflowInterface
  public interface LabeledWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class LabeledWorkflowImpl implements LabeledWorkflow {
    @Override
    public void execute() {
      Workflow.newEventGroup("group-id", EventGroupOptions.newBuilder().setLabel("hello").build())
          .run(() -> Workflow.sleep(Duration.ofMillis(1)));
    }
  }
}
