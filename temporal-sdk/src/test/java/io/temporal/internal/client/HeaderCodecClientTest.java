package io.temporal.internal.client;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.UpdateWorkflowExecutionLifecycleStage;
import io.temporal.api.update.v1.UpdateRef;
import io.temporal.api.update.v1.WaitPolicy;
import io.temporal.api.workflowservice.v1.ExecuteMultiOperationRequest;
import io.temporal.api.workflowservice.v1.ExecuteMultiOperationResponse;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.UpdateWorkflowExecutionResponse;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest.PrefixPayloadCodec;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.StartUpdateInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowStartInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowUpdateWithStartInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.payload.codec.PayloadCodec;
import java.util.Collections;
import java.util.Optional;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class HeaderCodecClientTest {
  @Test
  public void multioperationEncodesReusedHeaderOncePerOperation() {
    for (boolean enabled : new boolean[] {false, true}) {
      PayloadCodec codec = mock(PayloadCodec.class);
      PrefixPayloadCodec encoding = new PrefixPayloadCodec();
      when(codec.encode(any())).thenAnswer(call -> encoding.encode(call.getArgument(0)));
      when(codec.withContext(any())).thenReturn(codec);
      CodecDataConverter converter =
          new CodecDataConverter(
              DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(codec));
      WorkflowClientOptions options =
          WorkflowClientOptions.newBuilder()
              .setDataConverter(converter)
              .validateAndBuildWithDefaults();
      GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
      WorkflowExecution execution = WorkflowExecution.newBuilder().setWorkflowId("id").build();
      UpdateWorkflowExecutionLifecycleStage accepted =
          UpdateWorkflowExecutionLifecycleStage.UPDATE_WORKFLOW_EXECUTION_LIFECYCLE_STAGE_ACCEPTED;
      when(rpc.executeMultiOperation(any(), any()))
          .thenReturn(
              ExecuteMultiOperationResponse.newBuilder()
                  .addResponses(
                      ExecuteMultiOperationResponse.Response.newBuilder()
                          .setStartWorkflow(
                              StartWorkflowExecutionResponse.newBuilder().setRunId("run")))
                  .addResponses(
                      ExecuteMultiOperationResponse.Response.newBuilder()
                          .setUpdateWorkflow(
                              UpdateWorkflowExecutionResponse.newBuilder()
                                  .setStage(accepted)
                                  .setUpdateRef(
                                      UpdateRef.newBuilder()
                                          .setWorkflowExecution(execution)
                                          .setUpdateId("u"))))
                  .build());
      RootWorkflowClientInvoker invoker =
          new RootWorkflowClientInvoker(
              rpc,
              options.getNamespace(),
              options.getIdentity(),
              options.getQueryRejectCondition(),
              options.getContextPropagators(),
              options.getDataConverter(),
              new WorkerFactoryRegistry(),
              null);
      Payload payload = DefaultDataConverter.STANDARD_INSTANCE.toPayload("trace").get();
      Header header = Header.empty();
      header.putAll(Collections.singletonMap("_tracer-data", payload), enabled);
      invoker.updateWithStart(
          new WorkflowUpdateWithStartInput<>(
              new WorkflowStartInput(
                  "id",
                  "type",
                  header,
                  new Object[0],
                  WorkflowOptions.newBuilder().setTaskQueue("q").build()),
              new StartUpdateInput<>(
                  execution,
                  Optional.of("type"),
                  "update",
                  header,
                  "u",
                  new Object[0],
                  String.class,
                  String.class,
                  "",
                  WaitPolicy.newBuilder().setLifecycleStage(accepted).build())));

      ArgumentCaptor<ExecuteMultiOperationRequest> sent =
          ArgumentCaptor.forClass(ExecuteMultiOperationRequest.class);
      verify(rpc).executeMultiOperation(sent.capture(), any());
      Payload expected =
          enabled ? encoding.encode(Collections.singletonList(payload)).get(0) : payload;
      assertEquals(
          expected,
          sent.getValue()
              .getOperations(0)
              .getStartWorkflow()
              .getHeader()
              .getFieldsOrThrow("_tracer-data"));
      assertEquals(
          expected,
          sent.getValue()
              .getOperations(1)
              .getUpdateWorkflow()
              .getRequest()
              .getInput()
              .getHeader()
              .getFieldsOrThrow("_tracer-data"));
      assertEquals(payload, header.getValues().get("_tracer-data"));
      verify(codec, times(enabled ? 2 : 0)).encode(any());
    }
  }
}
