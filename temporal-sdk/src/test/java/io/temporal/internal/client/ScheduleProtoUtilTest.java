package io.temporal.internal.client;

import io.temporal.api.schedule.v1.ScheduleAction;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest.PrefixPayloadCodec;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.TransferTypeTestModel;
import io.temporal.common.interceptors.Header;
import java.time.Duration;
import java.util.Collections;
import org.junit.Assert;
import org.junit.Test;

public class ScheduleProtoUtilTest {
  private final ScheduleProtoUtil util =
      new ScheduleProtoUtil(null, "test-namespace", Collections.emptyList(), null);

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
  public void headerCodecRoundTripPreservesScheduleUpdates() {
    for (boolean enabled : new boolean[] {false, true}) {
      CodecDataConverter codecConverter =
          new CodecDataConverter(
              DefaultDataConverter.STANDARD_INSTANCE,
              Collections.singletonList(new PrefixPayloadCodec()),
              false);
      DataConverter converter = codecConverter;
      ScheduleProtoUtil util =
          new ScheduleProtoUtil(null, "namespace", Collections.emptyList(), converter);
      Header header = Header.empty();
      header.putAll(
          Collections.singletonMap(
              "_tracer-data", DefaultDataConverter.STANDARD_INSTANCE.toPayload("trace").get()),
          enabled);
      ScheduleActionStartWorkflow action =
          ScheduleActionStartWorkflow.newBuilder()
              .setWorkflowType("workflow")
              .setOptions(
                  WorkflowOptions.newBuilder().setWorkflowId("id").setTaskQueue("q").build())
              .setHeader(header)
              .build();
      ScheduleAction proto = util.actionToProto(action);
      Assert.assertEquals(
          enabled,
          proto
              .getStartWorkflow()
              .getHeader()
              .getFieldsOrThrow("_tracer-data")
              .getData()
              .startsWith(PrefixPayloadCodec.PREFIX));
      ScheduleActionStartWorkflow restored =
          (ScheduleActionStartWorkflow) util.protoToAction(proto);
      Assert.assertEquals(
          proto.getStartWorkflow().getHeader().getFieldsMap(), restored.getHeader().getValues());
      Assert.assertEquals(
          proto.getStartWorkflow().getHeader(),
          util.actionToProto(restored).getStartWorkflow().getHeader());
    }
  }

  @Test
  public void actionUsesInternalConverterWhileOptionsKeepConfiguredConverter() {
    DataConverter configured = DefaultDataConverter.newDefaultInstance();
    ScheduleClientOptions options =
        ScheduleClientOptions.newBuilder().setDataConverter(configured).build();
    ScheduleProtoUtil util =
        new ScheduleProtoUtil(
            null,
            options.getNamespace(),
            options.getContextPropagators(),
            ClientDataConverterFactory.forClient(configured));
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
