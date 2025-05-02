package io.temporal.workflow.deadlockdetector;

import static org.junit.Assert.*;

import io.temporal.api.common.v1.Payload;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class PayloadConverterEscapingDeadlockDetectionTest {
  public @Rule Timeout timeout = Timeout.seconds(20);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflows.DoNothingTestWorkflow1.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      new CodecDataConverter(
                          DefaultDataConverter.newDefaultInstance(),
                          Collections.singletonList(new IOSimulatingPayloadCodec())))
                  .build())
          .build();

  @Test
  public void
      testBlockingCodecThatCorrectlyDisablesDeadlockDetectorDoesNotPreventExecutionSuccess() {
    TestWorkflows.TestWorkflow1 testWorkflow1 =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflow1.class);
    long startNs = System.nanoTime();
    assertEquals(
        "Execution should be successful, IOSimulatingPayloadCodec disables deadlock detector during long blocking operations",
        "input",
        testWorkflow1.execute("input"));
    long completionNs = System.nanoTime();
    assertTrue(
        "Data conversion implementation should introduce a significant execution latency to this test",
        TimeUnit.NANOSECONDS.toSeconds(completionNs - startNs) > 5);
  }

  private static final class IOSimulatingPayloadCodec implements PayloadCodec {

    @Override
    @Nonnull
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      return WorkflowUnsafe.deadlockDetectorOff(
          () -> {
            try {
              Thread.sleep(2000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new RuntimeException(e);
            }
            return payloads;
          });
    }

    @Nonnull
    @Override
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      return WorkflowUnsafe.deadlockDetectorOff(
          () -> {
            try {
              Thread.sleep(2000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new RuntimeException(e);
            }
            return payloads;
          });
    }
  }
}
