package io.temporal.workflow.nexus;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.payload.visitor.SystemNexusPayloads;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.SerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote("Requires a server supporting the Temporal system Nexus workflow service.")
@Category(RequiresCloudProvisioning.class)
public class SystemSignalWithStartIntegrationTest {
  @Rule
  public SDKTestWorkflowRule rule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CallerImpl.class, ConcurrentCallerImpl.class, TargetImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      new CodecDataConverter(
                          DefaultDataConverter.STANDARD_INSTANCE,
                          Collections.singletonList(new DestinationCodec(null))))
                  .build())
          .build();

  @Before
  public void requireSystemService() {
    Assume.assumeTrue(
        "System Nexus requires a supporting external server",
        SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void startsThenSignalsTheExistingExecutionAndReplays() throws Exception {
    Caller caller = rule.newWorkflowStubTimeoutOptions(Caller.class);
    String target = caller.run();
    assertEquals(
        "start:first:second",
        rule.getWorkflowClient().newUntypedWorkflowStub(target).getResult(String.class));
    String callerId =
        io.temporal.client.WorkflowStub.fromTyped(caller).getExecution().getWorkflowId();
    WorkflowReplayer.replayWorkflowExecution(rule.getExecutionHistory(callerId), rule.getWorker());
  }

  @Test
  public void concurrentCallsUseDestinationCodecContextAndPreserveOuterEnvelopes()
      throws Exception {
    ConcurrentCaller caller = rule.newWorkflowStubTimeoutOptions(ConcurrentCaller.class);
    List<String> targets = caller.run();
    for (String target : targets) {
      assertEquals(
          "start:first:second",
          rule.getWorkflowClient().newUntypedWorkflowStub(target).getResult(String.class));
    }
    String callerId = WorkflowStub.fromTyped(caller).getExecution().getWorkflowId();
    int scheduled = 0;
    for (HistoryEvent event : rule.getExecutionHistory(callerId).getEvents()) {
      if (!event.hasNexusOperationScheduledEventAttributes()) continue;
      scheduled++;
      Payload envelope = event.getNexusOperationScheduledEventAttributes().getInput();
      assertTrue(SystemNexusPayloads.isSystemPayload(envelope));
      assertFalse(envelope.containsMetadata(DestinationCodec.KEY));
      SignalWithStartWorkflowExecutionRequest request =
          SignalWithStartWorkflowExecutionRequest.parseFrom(envelope.getData());
      assertTrue(targets.contains(request.getWorkflowId()));
      String expected = request.getNamespace() + "/" + request.getWorkflowId();
      List<Payload> embedded = new ArrayList<>();
      embedded.addAll(request.getInput().getPayloadsList());
      embedded.addAll(request.getSignalInput().getPayloadsList());
      embedded.addAll(request.getMemo().getFieldsMap().values());
      embedded.add(request.getUserMetadata().getSummary());
      embedded.add(request.getUserMetadata().getDetails());
      assertEquals(5, embedded.size());
      for (Payload payload : embedded) {
        assertEquals(expected, payload.getMetadataOrThrow(DestinationCodec.KEY).toStringUtf8());
      }
    }
    assertEquals(4, scheduled);
    WorkflowReplayer.replayWorkflowExecution(rule.getExecutionHistory(callerId), rule.getWorker());
  }

  @WorkflowInterface
  public interface ConcurrentCaller {
    @WorkflowMethod
    List<String> run();
  }

  public static class ConcurrentCallerImpl implements ConcurrentCaller {
    @Override
    public List<String> run() {
      List<String> targets =
          Arrays.asList(
              Workflow.getInfo().getWorkflowId() + "-first",
              Workflow.getInfo().getWorkflowId() + "-second");
      List<Promise<Void>> calls = new ArrayList<>();
      for (String id : targets) {
        calls.add(
            Async.procedure(
                () -> {
                  SignalWithStartWorkflowOptions options =
                      SignalWithStartWorkflowOptions.newBuilder()
                          .setId(id)
                          .setTaskQueue(Workflow.getInfo().getTaskQueue())
                          .setCronSchedule("")
                          .setIdConflictPolicy(
                              WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                          .setMemo(Collections.singletonMap("memo", "memo-value"))
                          .setStaticSummary("summary")
                          .setStaticDetails("details")
                          .build();
                  Target target = Workflow.newExternalWorkflowStub(Target.class, id);
                  Workflow.signalWithStartWorkflow(
                      target::run, "start", target::notifySignal, "first", options);
                  Workflow.signalWithStartWorkflow(
                      "SystemSignalTarget",
                      new Object[] {"ignored"},
                      "notifySignal",
                      new Object[] {"second"},
                      options);
                }));
      }
      Promise.allOf(calls).get();
      return targets;
    }
  }

  private static final class DestinationCodec implements PayloadCodec {
    static final String KEY = "test-destination";
    private final String destination;

    DestinationCodec(String destination) {
      this.destination = destination;
    }

    @Nonnull
    @Override
    public PayloadCodec withContext(@Nonnull SerializationContext context) {
      if (!(context instanceof WorkflowSerializationContext)) return this;
      WorkflowSerializationContext workflow = (WorkflowSerializationContext) context;
      return new DestinationCodec(workflow.getNamespace() + "/" + workflow.getWorkflowId());
    }

    @Nonnull
    @Override
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      return payloads.stream()
          .map(
              payload -> {
                rejectEnvelope(payload);
                assertFalse("Payload encoded twice", payload.containsMetadata(KEY));
                return destination == null
                    ? payload
                    : payload.toBuilder()
                        .putMetadata(KEY, ByteString.copyFromUtf8(destination))
                        .build();
              })
          .collect(Collectors.toList());
    }

    @Nonnull
    @Override
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      return payloads.stream()
          .map(
              payload -> {
                rejectEnvelope(payload);
                if (!payload.containsMetadata(KEY)) return payload;
                assertEquals(destination, payload.getMetadataOrThrow(KEY).toStringUtf8());
                return payload.toBuilder().removeMetadata(KEY).build();
              })
          .collect(Collectors.toList());
    }

    private static void rejectEnvelope(Payload payload) {
      assertFalse(
          "System envelope passed to the user codec", SystemNexusPayloads.isSystemPayload(payload));
      ByteString type = payload.getMetadataMap().get("messageType");
      assertFalse(
          "Unmarked system response passed to the user codec",
          type != null
              && type.toStringUtf8()
                  .startsWith("temporal.api.workflowservice.v1.SignalWithStartWorkflowExecution"));
    }
  }

  @WorkflowInterface
  public interface Target {
    @WorkflowMethod(name = "SystemSignalTarget")
    @io.temporal.common.MethodRetry(initialIntervalSeconds = 1, maximumAttempts = 3)
    @io.temporal.common.CronSchedule("0 * * * *")
    String run(String initial);

    @SignalMethod
    void notifySignal(String value);
  }

  public static class TargetImpl implements Target {
    private final java.util.List<String> signals = new java.util.ArrayList<>();

    @Override
    public String run(String initial) {
      Workflow.await(() -> signals.size() == 2);
      return initial + ":" + String.join(":", signals);
    }

    @Override
    public void notifySignal(String value) {
      signals.add(value);
    }
  }

  @WorkflowInterface
  public interface Caller {
    @WorkflowMethod
    String run();
  }

  public static class CallerImpl implements Caller {
    @Override
    public String run() {
      String id = Workflow.getInfo().getWorkflowId() + "-target";
      SignalWithStartWorkflowOptions options =
          SignalWithStartWorkflowOptions.newBuilder()
              .setId(id)
              .setTaskQueue(Workflow.getInfo().getTaskQueue())
              .setCronSchedule("")
              .setIdConflictPolicy(
                  WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
              .build();
      Target target = Workflow.newExternalWorkflowStub(Target.class, id);
      ExternalWorkflowStub external =
          Workflow.signalWithStartWorkflow(
              target::run, "start", target::notifySignal, "first", options);
      assertEquals(id, external.getExecution().getWorkflowId());
      Async.function(
              () ->
                  Workflow.signalWithStartWorkflow(
                      "SystemSignalTarget",
                      new Object[] {"ignored"},
                      "notifySignal",
                      new Object[] {"second"},
                      options))
          .get();
      return id;
    }
  }
}
