package io.temporal.internal.client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Deadline;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.NexusOperationWaitStage;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.workflowservice.v1.PollNexusOperationExecutionRequest;
import io.temporal.api.workflowservice.v1.PollNexusOperationExecutionResponse;
import io.temporal.client.NexusClientOptions;
import io.temporal.client.NexusOperationFailedException;
import io.temporal.common.converter.GlobalDataConverter;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.GetNexusOperationResultInput;
import io.temporal.common.interceptors.NexusClientCallsInterceptor.GetNexusOperationResultOutput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

/**
 * Server-free unit tests for {@link RootNexusClientInvoker} poll-result extraction. The end-to-end
 * Nexus suites are gated on an external service; these mock {@link GenericWorkflowClient} so the
 * outcome handling (result / failure / null-result / malformed) is covered in standard CI.
 */
public class RootNexusClientInvokerTest {

  private final GenericWorkflowClient genericClient = mock(GenericWorkflowClient.class);
  private final RootNexusClientInvoker invoker =
      new RootNexusClientInvoker(genericClient, NexusClientOptions.getDefaultInstance());

  private static GetNexusOperationResultInput<String> input() {
    return new GetNexusOperationResultInput<>(
        "op-1", null, Deadline.after(10, TimeUnit.SECONDS), String.class, String.class);
  }

  private void stubClosedPoll(PollNexusOperationExecutionResponse.Builder response) {
    when(genericClient.pollNexusOperationExecution(
            any(PollNexusOperationExecutionRequest.class), any(Deadline.class)))
        .thenReturn(
            response
                .setWaitStage(NexusOperationWaitStage.NEXUS_OPERATION_WAIT_STAGE_CLOSED)
                .build());
  }

  @Test
  public void closedWithResultReturnsDeserializedValue() throws Exception {
    Payload payload = GlobalDataConverter.get().toPayload("hello").get();
    stubClosedPoll(PollNexusOperationExecutionResponse.newBuilder().setResult(payload));

    GetNexusOperationResultOutput<String> out = invoker.getNexusOperationResult(input());

    Assert.assertEquals("hello", out.getResult());
  }

  @Test
  public void closedWithNullResultPayloadReturnsNull() throws Exception {
    // A null / Void success arrives as a PRESENT, null-encoded result payload that deserializes to
    // null — distinct from the no-outcome malformed case below.
    Payload nullPayload = GlobalDataConverter.get().toPayload(null).get();
    stubClosedPoll(PollNexusOperationExecutionResponse.newBuilder().setResult(nullPayload));

    GetNexusOperationResultOutput<String> out = invoker.getNexusOperationResult(input());

    Assert.assertNull(out.getResult());
  }

  @Test
  public void closedWithFailureThrows() {
    stubClosedPoll(
        PollNexusOperationExecutionResponse.newBuilder()
            .setFailure(Failure.newBuilder().setMessage("boom").build()));

    Assert.assertThrows(
        NexusOperationFailedException.class, () -> invoker.getNexusOperationResult(input()));
  }

  @Test
  public void closedWithNoOutcomeThrows() throws Exception {
    // A closed operation must carry either a result or a failure; neither set is a malformed
    // response and must surface as an error rather than a silent null.
    stubClosedPoll(PollNexusOperationExecutionResponse.newBuilder());

    Assert.assertThrows(
        NexusOperationFailedException.class, () -> invoker.getNexusOperationResult(input()));
  }
}
