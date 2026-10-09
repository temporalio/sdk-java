package io.temporal.internal.client;

import io.temporal.api.schedule.v1.ScheduleAction;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.TransferTypeTestModel;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Test;

public class ScheduleProtoUtilTest {
  private final ScheduleProtoUtil util = new ScheduleProtoUtil(null, null, null);

  @Test
  public void policyToProtoOmitsDefaultCatchupWindow() {
    Assert.assertFalse(util.policyToProto(SchedulePolicy.newBuilder().build()).hasCatchupWindow());
  }

  @Test
  public void policyToProtoIncludesExplicitCatchupWindow() {
    Assert.assertEquals(
        300L,
        util.policyToProto(
                SchedulePolicy.newBuilder().setCatchupWindow(Duration.ofMinutes(5)).build())
            .getCatchupWindow()
            .getSeconds());
  }

  @Test
  public void actionUsesInternalConverterWhileOptionsKeepConfiguredConverter() {
    DataConverter configured = DefaultDataConverter.newDefaultInstance();
    ScheduleClientOptions options =
        ScheduleClientOptions.newBuilder().setDataConverter(configured).build();
    ScheduleProtoUtil util =
        new ScheduleProtoUtil(null, options, ClientDataConverterFactory.forClient(configured));
    ScheduleActionStartWorkflow action =
        ScheduleActionStartWorkflow.newBuilder()
            .setWorkflowType("workflow-type")
            .setArguments(new TransferTypeTestModel("value"))
            .setOptions(
                WorkflowOptions.newBuilder().setWorkflowId("id").setTaskQueue("queue").build())
            .build();

    ScheduleAction proto = util.actionToProto(action);

    Assert.assertSame(configured, options.getDataConverter());
    Assert.assertEquals(
        "google.protobuf.StringValue",
        proto
            .getStartWorkflow()
            .getInput()
            .getPayloads(0)
            .getMetadataOrThrow("messageType")
            .toStringUtf8());
  }
}
