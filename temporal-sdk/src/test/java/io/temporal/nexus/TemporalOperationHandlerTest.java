package io.temporal.nexus;

import io.nexusrpc.handler.OperationCancelDetails;
import io.nexusrpc.handler.OperationContext;
import io.temporal.internal.nexus.OperationTokenUtil;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assert;
import org.junit.Test;

public class TemporalOperationHandlerTest {

  @Test
  public void cancelDispatchesTypedInputToContextFreeOverride() throws Exception {
    AtomicReference<CancelActivityExecutionInput> receivedInput = new AtomicReference<>();
    TemporalOperationHandler<String, String> handler =
        new TemporalOperationHandler<String, String>(
            (client, input) -> TemporalOperationResult.sync(input)) {
          @Override
          protected void cancelActivityExecution(CancelActivityExecutionInput input) {
            receivedInput.set(input);
          }
        };
    String operationToken =
        OperationTokenUtil.generateActivityExecutionOperationToken(
            "activity-id", "run-id", "namespace");

    handler.cancel(
        OperationContext.newBuilder().setService("service").setOperation("operation").build(),
        OperationCancelDetails.newBuilder().setOperationToken(operationToken).build());

    Assert.assertNotNull(receivedInput.get());
    Assert.assertEquals("activity-id", receivedInput.get().getActivityId());
    Assert.assertEquals("run-id", receivedInput.get().getRunId());
  }
}
