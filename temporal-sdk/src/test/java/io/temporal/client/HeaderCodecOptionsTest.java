package io.temporal.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.uber.m3.tally.NoopScope;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleClientPlugin;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest.PrefixPayloadCodec;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DataConverterWithCodec;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.converter.HeaderEncodingDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.common.HeaderUtils;
import io.temporal.internal.common.converter.TemporalTransferTypeDataConverter;
import io.temporal.internal.payload.storage.ExternalStorageDataConverter;
import io.temporal.internal.worker.SingleWorkerOptions;
import io.temporal.payload.codec.ChainCodec;
import io.temporal.payload.codec.HeaderCodecProvider;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;

public class HeaderCodecOptionsTest {
  @Test
  public void disabledClientsDoNotInspectCustomConverterCapability() {
    DataConverterWithCodec custom = mock(DataConverterWithCodec.class);
    assertNull(HeaderUtils.resolveHeaderCodec(custom));
    assertNull(
        HeaderUtils.resolveHeaderCodec(
            new ExternalStorageDataConverter(
                TemporalTransferTypeDataConverter.wrap(custom), null)));
    verifyNoInteractions(custom);
  }

  @Test
  public void enabledOptionsAndWorkerCopiesPreservePolicyAndCodec() {
    CodecDataConverter ordinary =
        new CodecDataConverter(
            DefaultDataConverter.STANDARD_INSTANCE,
            Collections.singletonList(new PrefixPayloadCodec()));
    assertNull(HeaderUtils.resolveHeaderCodec(ordinary));
    HeaderEncodingDataConverter converter = new HeaderEncodingDataConverter(ordinary);
    SingleWorkerOptions worker =
        SingleWorkerOptions.newBuilder()
            .setDataConverter(converter)
            .setHeaderCodec(HeaderUtils.resolveHeaderCodec(converter))
            .build();
    assertSame(
        worker.getHeaderCodec(), SingleWorkerOptions.newBuilder(worker).build().getHeaderCodec());
    assertSame(
        converter.getHeaderCodec(),
        HeaderUtils.resolveHeaderCodec(
            new ExternalStorageDataConverter(
                TemporalTransferTypeDataConverter.wrap(converter), null)));
    DataConverter forwarding =
        mock(DataConverter.class, withSettings().extraInterfaces(HeaderCodecProvider.class));
    when(((HeaderCodecProvider) forwarding).getHeaderCodec())
        .thenReturn(converter.getHeaderCodec());
    assertSame(converter.getHeaderCodec(), HeaderUtils.resolveHeaderCodec(forwarding));
    assertNull(HeaderUtils.resolveHeaderCodec(mock(DataConverter.class)));
  }

  @Test
  public void wrapperRejectsMissingNullAndEmptyCodecStages() {
    DataConverterWithCodec nullProvider = mock(DataConverterWithCodec.class);
    DataConverterWithCodec emptyProvider = mock(DataConverterWithCodec.class);
    when(emptyProvider.getPayloadCodec()).thenReturn(new ChainCodec(Collections.emptyList()));
    assertThrows(NullPointerException.class, () -> new HeaderEncodingDataConverter(null));
    for (DataConverterWithCodec unavailable :
        new DataConverterWithCodec[] {
          nullProvider,
          emptyProvider,
          new CodecDataConverter(DefaultDataConverter.STANDARD_INSTANCE, Collections.emptyList()),
          new CodecDataConverter(
              DefaultDataConverter.STANDARD_INSTANCE,
              Collections.singletonList(new ChainCodec(Collections.emptyList())))
        }) {
      assertThrows(
          IllegalArgumentException.class, () -> new HeaderEncodingDataConverter(unavailable));
    }
  }

  @Test
  public void schedulePluginsCanInstallHeaderWrapperBeforeCodecResolution() {
    WorkflowServiceStubs service = scheduleService();
    ScheduleClientPlugin plugin = mock(ScheduleClientPlugin.class);
    when(plugin.getName()).thenReturn("header-codec");
    PayloadCodec codec = mock(PayloadCodec.class);
    PayloadCodecException codecFailure = new PayloadCodecException("plugin header codec");
    when(codec.withContext(any())).thenReturn(codec);
    when(codec.encode(any())).thenThrow(codecFailure);
    DataConverter converter =
        new HeaderEncodingDataConverter(
            new CodecDataConverter(
                DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(codec)));
    doAnswer(
            call -> {
              ScheduleClientOptions.Builder builder = call.getArgument(0);
              return builder.setDataConverter(converter);
            })
        .when(plugin)
        .configureScheduleClient(any());
    ScheduleClientOptions options = ScheduleClientOptions.newBuilder().setPlugins(plugin).build();
    Schedule schedule =
        Schedule.newBuilder()
            .setSpec(ScheduleSpec.newBuilder().build())
            .setAction(
                ScheduleActionStartWorkflow.newBuilder()
                    .setWorkflowType("workflow")
                    .setOptions(
                        WorkflowOptions.newBuilder()
                            .setWorkflowId("workflow")
                            .setTaskQueue("q")
                            .build())
                    .setHeader(
                        new Header(
                            Collections.singletonMap(
                                "trace",
                                DefaultDataConverter.STANDARD_INSTANCE.toPayload("context").get())))
                    .build())
            .build();
    assertSame(
        codecFailure,
        assertThrows(
            PayloadCodecException.class,
            () ->
                ScheduleClient.newInstance(service, options)
                    .createSchedule("schedule", schedule, ScheduleOptions.newBuilder().build())));
  }

  @Test
  public void workflowPluginsCanInstallHeaderWrapper() {
    HeaderEncodingDataConverter converter =
        new HeaderEncodingDataConverter(
            new CodecDataConverter(
                DefaultDataConverter.STANDARD_INSTANCE,
                Collections.singletonList(new PrefixPayloadCodec())));
    WorkflowClientPlugin plugin = mock(WorkflowClientPlugin.class);
    when(plugin.getName()).thenReturn("header-codec");
    doAnswer(
            call ->
                ((WorkflowClientOptions.Builder) call.getArgument(0)).setDataConverter(converter))
        .when(plugin)
        .configureWorkflowClient(any());
    WorkflowClient client =
        WorkflowClient.newInstance(
            scheduleService(), WorkflowClientOptions.newBuilder().setPlugins(plugin).build());
    assertSame(
        converter.getHeaderCodec(),
        HeaderUtils.resolveHeaderCodec(client.getOptions().getDataConverter()));
  }

  @Test
  public void wrapperDelegatesValuesFailuresAndSerializationContexts() {
    DataConverterWithCodec delegate = mock(DataConverterWithCodec.class);
    DataConverter contextual = mock(DataConverter.class);
    PayloadCodec codec = mock(PayloadCodec.class);
    PayloadCodec scopedCodec = new PrefixPayloadCodec();
    WorkflowSerializationContext context =
        new WorkflowSerializationContext("namespace", "workflow");
    when(delegate.getPayloadCodec()).thenReturn(codec);
    when(delegate.withContext(context)).thenReturn(contextual);
    when(codec.withContext(context)).thenReturn(scopedCodec);
    HeaderEncodingDataConverter converter =
        new HeaderEncodingDataConverter(delegate).withContext(context);
    assertSame(scopedCodec, converter.getHeaderCodec());
    Payload payload = Payload.getDefaultInstance();
    Optional<Payloads> payloads = Optional.of(Payloads.getDefaultInstance());
    Class<?>[] types = {String.class};
    java.lang.reflect.Type[] genericTypes = {String.class};
    when(contextual.toPayload("value")).thenReturn(Optional.of(payload));
    when(contextual.fromPayload(payload, String.class, String.class)).thenReturn("value");
    when(contextual.toPayloads("value")).thenReturn(payloads);
    when(contextual.fromPayloads(0, payloads, String.class, String.class)).thenReturn("value");
    Object[] values = {"value"};
    when(contextual.fromPayloads(payloads, types, genericTypes)).thenReturn(values);
    assertEquals(Optional.of(payload), converter.toPayload("value"));
    assertEquals("value", converter.fromPayload(payload, String.class, String.class));
    assertSame(payloads, converter.toPayloads("value"));
    assertEquals("value", converter.fromPayloads(0, payloads, String.class, String.class));
    assertSame(values, converter.fromPayloads(payloads, types, genericTypes));
    RuntimeException exception = new RuntimeException("failure");
    Failure failure = Failure.getDefaultInstance();
    when(contextual.exceptionToFailure(exception)).thenReturn(failure);
    when(contextual.failureToException(failure)).thenReturn(exception);
    assertSame(failure, converter.exceptionToFailure(exception));
    assertSame(exception, converter.failureToException(failure));
  }

  private static WorkflowServiceStubs scheduleService() {
    WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
    WorkflowServiceStubsOptions options = mock(WorkflowServiceStubsOptions.class);
    when(service.getOptions()).thenReturn(options);
    when(options.getMetricsScope()).thenReturn(new NoopScope());
    return service;
  }
}
