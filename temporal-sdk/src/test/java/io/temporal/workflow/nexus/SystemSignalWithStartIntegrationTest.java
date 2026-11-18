package io.temporal.workflow.nexus;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.sdk.v1.ExternalStorageReference;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptorBase;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.internal.payload.storage.TestStorageDriver;
import io.temporal.internal.payload.visitor.SystemNexusPayloads;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.SerializationContext;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.testing.internal.TracingWorkerInterceptor;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@CloudTestExclusionNote("Requires a server supporting the Temporal system Nexus workflow service.")
@Category(RequiresCloudProvisioning.class)
@RunWith(Parameterized.class)
public class SystemSignalWithStartIntegrationTest {
  @Parameterized.Parameters(name = "externalStorage={0}")
  public static Object[] storageModes() {
    return new Object[] {false, true};
  }

  private final boolean externalStorageEnabled;
  private final TestStorageDriver storageDriver = TestStorageDriver.named("signal-with-start");
  private final ExternalStorage storage =
      ExternalStorage.newBuilder().setDriver(storageDriver).setPayloadSizeThreshold(0).build();

  @Rule public final SDKTestWorkflowRule rule;

  public SystemSignalWithStartIntegrationTest(boolean externalStorageEnabled) {
    this.externalStorageEnabled = externalStorageEnabled;
    rule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowTypes(CallerImpl.class, ConcurrentCallerImpl.class, TargetImpl.class)
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder()
                    .setWorkerInterceptors(
                        new WorkerInterceptorBase() {
                          @Override
                          public WorkflowInboundCallsInterceptor interceptWorkflow(
                              WorkflowInboundCallsInterceptor next) {
                            return new WorkflowInboundCallsInterceptorBase(next) {
                              @Override
                              public void init(WorkflowOutboundCallsInterceptor outbound) {
                                super.init(
                                    new WorkflowOutboundCallsInterceptorBase(outbound) {
                                      @Override
                                      public <R>
                                          ExecuteNexusOperationOutput<R> executeNexusOperation(
                                              ExecuteNexusOperationInput<R> input) {
                                        throw new AssertionError(
                                            "System operations must bypass ordinary Nexus interception");
                                      }
                                    });
                              }
                            };
                          }
                        },
                        new TracingWorkerInterceptor(
                            new TracingWorkerInterceptor.FilteredTrace() {
                              @Override
                              public boolean add(String event) {
                                assertFalse(
                                    "Direct implementations must also bypass ordinary Nexus hooks",
                                    event.startsWith("executeNexusOperation "));
                                return super.add(event);
                              }
                            }))
                    .build())
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setExternalStorage(externalStorageEnabled ? storage : null)
                    .setDataConverter(
                        new CodecDataConverter(
                            DefaultDataConverter.STANDARD_INSTANCE,
                            Collections.singletonList(new DestinationCodec(null))))
                    .build())
            .build();
  }

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
    assertStorageUsed();
    String callerId =
        io.temporal.client.WorkflowStub.fromTyped(caller).getExecution().getWorkflowId();
    WorkflowReplayer.replayWorkflowExecution(rule.getExecutionHistory(callerId), rule.getWorker());
    replayTarget(target);
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
    assertStorageUsed();
    String callerId = WorkflowStub.fromTyped(caller).getExecution().getWorkflowId();
    int scheduled = 0;
    for (HistoryEvent event : rule.getExecutionHistory(callerId).getEvents()) {
      if (!event.hasNexusOperationScheduledEventAttributes()) continue;
      scheduled++;
      Payload envelope = event.getNexusOperationScheduledEventAttributes().getInput();
      assertTrue(SystemNexusPayloads.isSystemPayload(envelope));
      assertFalse(envelope.containsMetadata(DestinationCodec.KEY));
      assertEquals(0, envelope.getExternalPayloadsCount());
      SignalWithStartWorkflowExecutionRequest request =
          SignalWithStartWorkflowExecutionRequest.parseFrom(envelope.getData());
      assertTrue(targets.contains(request.getWorkflowId()));
      String expected = request.getNamespace() + "/" + request.getWorkflowId();
      List<Payload> embedded = embeddedPayloads(request);
      assertEquals(5, embedded.size());
      if (externalStorageEnabled) {
        for (Payload payload : embedded) {
          assertEquals(
              ExternalStorageReference.getDescriptor().getFullName(),
              payload.getMetadataOrThrow("messageType").toStringUtf8());
          assertTrue(payload.getExternalPayloadsCount() > 0);
        }
        request =
            ExternalStorageRunner.create(storage, request.getNamespace())
                .retrieve(request, CancellationToken.none());
        embedded = embeddedPayloads(request);
      }
      for (Payload payload : embedded) {
        assertEquals(expected, payload.getMetadataOrThrow(DestinationCodec.KEY).toStringUtf8());
      }
    }
    assertEquals(4, scheduled);
    WorkflowReplayer.replayWorkflowExecution(rule.getExecutionHistory(callerId), rule.getWorker());
    for (String target : targets) {
      replayTarget(target);
    }
  }

  private void replayTarget(String workflowId) throws Exception {
    int retrievesBeforeReplay = storageDriver.retrieves.get();
    WorkflowReplayer.replayWorkflowExecution(
        rule.getExecutionHistory(workflowId), rule.getWorker());
    if (externalStorageEnabled) {
      assertTrue(
          "Target replay must retrieve the stored workflow and signal inputs",
          storageDriver.retrieves.get() > retrievesBeforeReplay);
    }
  }

  private void assertStorageUsed() {
    if (externalStorageEnabled) {
      assertTrue("Workflow execution must offload payloads", storageDriver.stores.get() > 0);
      assertTrue("Workflow execution must retrieve payloads", storageDriver.retrieves.get() > 0);
      for (Payload stored : storageDriver.storedPayloads()) {
        assertFalse(
            "The outer system envelope must remain inline",
            SystemNexusPayloads.isSystemPayload(stored));
      }
    } else {
      assertEquals(0, storageDriver.stores.get());
      assertEquals(0, storageDriver.retrieves.get());
    }
  }

  private static List<Payload> embeddedPayloads(SignalWithStartWorkflowExecutionRequest request) {
    List<Payload> payloads = new ArrayList<>();
    payloads.addAll(request.getInput().getPayloadsList());
    payloads.addAll(request.getSignalInput().getPayloadsList());
    payloads.addAll(request.getMemo().getFieldsMap().values());
    payloads.add(request.getUserMetadata().getSummary());
    payloads.add(request.getUserMetadata().getDetails());
    return payloads;
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
                          .setTaskQueue(Workflow.getInfo().getTaskQueue())
                          .setCronSchedule("")
                          .setWorkflowIdConflictPolicy(
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
                      options.toBuilder().setWorkflowId(id).build());
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
              .setCronSchedule("")
              .setWorkflowIdConflictPolicy(
                  WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
              .build();
      Target target = Workflow.newExternalWorkflowStub(Target.class, id);
      ExternalWorkflowStub external =
          Workflow.signalWithStartWorkflow(
              target::run, "start", target::notifySignal, "first", options);
      assertEquals(id, external.getExecution().getWorkflowId());
      Workflow.signalWithStartWorkflow(
          "SystemSignalTarget",
          new Object[] {"ignored"},
          "notifySignal",
          new Object[] {"second"},
          options.toBuilder().setWorkflowId(id).build());
      return id;
    }
  }
}
