package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
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
          .setWorkflowTypes(LabelPayloadWorkflowImpl.class)
          .build();

  @Test
  public void labelIsCodecEncodedAndIdIsNot() {
    LabelPayloadWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(LabelPayloadWorkflow.class);
    workflow.execute();

    HistoryEvent activityA = null;
    HistoryEvent activityB = null;
    for (HistoryEvent event :
        testWorkflowRule
            .getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId())
            .getEvents()) {
      if (event.getEventType() != EventType.EVENT_TYPE_TIMER_STARTED) {
        continue;
      }
      String summary =
          testWorkflowRule
              .getWorkflowClient()
              .getOptions()
              .getDataConverter()
              .fromPayload(event.getUserMetadata().getSummary(), String.class, String.class);
      if ("activity-a".equals(summary)) {
        activityA = event;
      } else if ("activity-b".equals(summary)) {
        activityB = event;
      }
    }

    // EG-LABEL-PAYLOAD-21: Label ID is not codec-encoded
    assertEquals("aaa", activityA.getEventGroupMarkers(0).getLabel().getId());
    assertEquals("bbb", activityB.getEventGroupMarkers(0).getLabel().getId());
    assertFalse(activityB.getEventGroupMarkers(0).getLabel().getId().contains("ENCODED"));
    Payload label = activityB.getEventGroupMarkers(0).getLabel().getLabel();

    // EG-LABEL-PAYLOAD-20: Label Payload is codec-encoded
    assertTrue(label.getData().startsWith(CodecDataConverterTest.PrefixPayloadCodec.PREFIX));
    assertEquals(
        "Label B",
        testWorkflowRule
            .getWorkflowClient()
            .getOptions()
            .getDataConverter()
            .fromPayload(label, String.class, String.class));
  }

  @WorkflowInterface
  public interface LabelPayloadWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class LabelPayloadWorkflowImpl implements LabelPayloadWorkflow {
    @Override
    public void execute() {
      EventGroup a = Workflow.newEventGroup("aaa");
      EventGroup b =
          Workflow.newEventGroup("bbb", EventGroupOptions.newBuilder().setLabel("Label B").build());

      Workflow.sleep(
          Duration.ofMillis(1),
          TimerOptions.newBuilder().setSummary("activity-a").addEventGroups(a).build());
      Workflow.sleep(
          Duration.ofMillis(1),
          TimerOptions.newBuilder().setSummary("activity-b").addEventGroups(b).build());
    }
  }
}
