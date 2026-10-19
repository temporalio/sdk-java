package io.temporal.internal.payload.visitor;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.Test;

public class SystemNexusPayloadTest {
  private static Payload payload(String value) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(value)).build();
  }

  @Test
  public void visitsNestedSequencesBeforeRepacking() throws Exception {
    SignalWithStartWorkflowExecutionRequest request =
        SignalWithStartWorkflowExecutionRequest.newBuilder()
            .setInput(Payloads.newBuilder().addPayloads(payload("workflow")))
            .setSignalInput(Payloads.newBuilder().addPayloads(payload("signal")))
            .build();
    CompletableFuture<java.util.List<Payload>> workflow = new CompletableFuture<>();
    Payloads outer = Payloads.newBuilder().addPayloads(SystemNexusPayloads.pack(request)).build();
    CompletableFuture<Payloads> result =
        PayloadVisitors.visit(
            outer,
            PayloadVisitorOptions.newBuilder(
                    (context, values) -> {
                      if (values.get(0).getData().toStringUtf8().equals("workflow")) {
                        return workflow;
                      }
                      return CompletableFuture.completedFuture(
                          Collections.singletonList(payload("SIGNAL")));
                    })
                .build());
    assertFalse(result.isDone());
    workflow.complete(Arrays.asList(payload("ONE"), payload("TWO")));
    SignalWithStartWorkflowExecutionRequest decoded =
        SignalWithStartWorkflowExecutionRequest.parseFrom(result.join().getPayloads(0).getData());
    assertEquals(2, decoded.getInput().getPayloadsCount());
    assertEquals("SIGNAL", decoded.getSignalInput().getPayloads(0).getData().toStringUtf8());
    assertEquals(
        outer.getPayloads(0).getMetadataMap(), result.join().getPayloads(0).getMetadataMap());
  }

  @Test
  public void handlesPayloadFreeResponses() {
    Payload response =
        SystemNexusPayloads.pack(
            SignalWithStartWorkflowExecutionResponse.newBuilder().setRunId("run").build());
    Payloads outer = Payloads.newBuilder().addPayloads(response).build();
    assertEquals(
        outer,
        PayloadVisitors.visit(
                outer,
                PayloadVisitorOptions.newBuilder(
                        (context, values) -> {
                          throw new AssertionError("Outer envelope was visited");
                        })
                    .build())
            .join());
  }

  @Test
  public void rejectsUnknownAndMalformedMarkedPayloads() {
    Payload unknown =
        SystemNexusPayloads.pack(SignalWithStartWorkflowExecutionRequest.getDefaultInstance())
            .toBuilder()
            .putMetadata("messageType", ByteString.copyFromUtf8("unknown.Message"))
            .build();
    Payload malformed =
        SystemNexusPayloads.pack(SignalWithStartWorkflowExecutionRequest.getDefaultInstance())
            .toBuilder()
            .setData(ByteString.copyFrom(new byte[] {(byte) 0xff}))
            .build();
    for (Payload value : Arrays.asList(unknown, malformed)) {
      CompletionException error =
          assertThrows(
              CompletionException.class,
              () ->
                  PayloadVisitors.visit(
                          Payloads.newBuilder().addPayloads(value).build(),
                          PayloadVisitorOptions.newBuilder(
                                  (context, values) -> CompletableFuture.completedFuture(values))
                              .build())
                      .join());
      assertTrue(error.getCause() instanceof VisitorException);
    }
  }
}
