package io.temporal.internal.client;

import static io.temporal.payload.storage.ExternalStorageTestUtils.encodingReferences;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.QueryWorkflowRequest;
import io.temporal.api.workflowservice.v1.QueryWorkflowResponse;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWithStartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.CodecDataConverterTest.PrefixPayloadCodec;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.QueryInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowSignalWithStartInput;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor.WorkflowStartInput;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.payload.storage.ExternalStorage;
import io.temporal.payload.storage.StorageDriver;
import io.temporal.payload.storage.StorageDriverClaim;
import io.temporal.payload.storage.StorageDriverRetrieveContext;
import io.temporal.payload.storage.StorageDriverStoreContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.payload.storage.StorageDriverWorkflowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class RootWorkflowClientInvokerStorageTargetTest {

  private static final String NAMESPACE = "test-namespace";
  private static final String REFERENCE_MARKER = "__temporal_external_storage_reference";

  @Test
  public void startCarriesTheWorkflowTypeButNoRunIdYet() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.start(any())).thenReturn(StartWorkflowExecutionResponse.getDefaultInstance());

    invoker(rpc, driver)
        .start(
            new WorkflowStartInput(
                "wf-1",
                "MyWorkflowType",
                Header.empty(),
                new Object[] {"argument"},
                WorkflowOptions.newBuilder().setTaskQueue("tq").build()));

    StorageDriverWorkflowInfo target = (StorageDriverWorkflowInfo) driver.lastTarget;
    assertEquals(NAMESPACE, target.getNamespace());
    assertEquals("wf-1", target.getId());
    assertEquals("MyWorkflowType", target.getType());
    assertNull(target.getRunId());
  }

  @Test
  public void signalCarriesTheRunId() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);

    invoker(rpc, driver)
        .signal(
            new WorkflowSignalInput(
                WorkflowExecution.newBuilder().setWorkflowId("wf-2").setRunId("run-9").build(),
                "mySignal",
                Header.empty(),
                new Object[] {"argument"}));

    StorageDriverWorkflowInfo target = (StorageDriverWorkflowInfo) driver.lastTarget;
    assertEquals("wf-2", target.getId());
    assertEquals("run-9", target.getRunId());
  }

  @Test
  public void anAbsentRunIdArrivesAsNullNotEmptyString() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);

    invoker(rpc, driver)
        .signal(
            new WorkflowSignalInput(
                WorkflowExecution.newBuilder().setWorkflowId("wf-3").build(),
                "mySignal",
                Header.empty(),
                new Object[] {"argument"}));

    assertNull(((StorageDriverWorkflowInfo) driver.lastTarget).getRunId());
  }

  @Test
  public void signalOffloadsHeaderPayloads() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    ArgumentCaptor<SignalWorkflowExecutionRequest> sent =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);

    invoker(rpc, driver)
        .signal(
            new WorkflowSignalInput(
                WorkflowExecution.newBuilder().setWorkflowId("wf-h").setRunId("run-h").build(),
                "mySignal",
                headerWith("trace", "some-tracing-context"),
                new Object[] {"argument"}));

    verify(rpc).signal(sent.capture());
    Payload original = tracePayload("some-tracing-context");
    assertTrue("the header value must reach the driver", driver.stored.contains(original));
    assertNotEquals(
        "the sent header must be a reference, not the original bytes",
        original,
        sent.getValue().getHeader().getFieldsOrThrow("trace"));
  }

  @Test
  public void queryOffloadsHeaderPayloads() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.query(any())).thenReturn(QueryWorkflowResponse.getDefaultInstance());
    ArgumentCaptor<QueryWorkflowRequest> sent = ArgumentCaptor.forClass(QueryWorkflowRequest.class);

    invoker(rpc, driver)
        .query(
            new QueryInput<>(
                WorkflowExecution.newBuilder().setWorkflowId("wf-q").build(),
                "myQuery",
                headerWith("trace", "some-tracing-context"),
                new Object[] {"argument"},
                String.class,
                String.class));

    verify(rpc).query(sent.capture());
    Payload original = tracePayload("some-tracing-context");
    assertTrue("the header value must reach the driver", driver.stored.contains(original));
    assertNotEquals(
        "the sent header must be a reference, not the original bytes",
        original,
        sent.getValue().getQuery().getHeader().getFieldsOrThrow("trace"));
  }

  @Test
  public void startOffloadsHeaderPayloads() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.start(any())).thenReturn(StartWorkflowExecutionResponse.getDefaultInstance());
    ArgumentCaptor<StartWorkflowExecutionRequest> sent =
        ArgumentCaptor.forClass(StartWorkflowExecutionRequest.class);

    invoker(rpc, driver)
        .start(
            new WorkflowStartInput(
                "wf-s",
                "MyWorkflowType",
                headerWith("trace", "some-tracing-context"),
                new Object[] {"argument"},
                WorkflowOptions.newBuilder().setTaskQueue("tq").build()));

    verify(rpc).start(sent.capture());
    Payload original = tracePayload("some-tracing-context");
    assertTrue("the header value must reach the driver", driver.stored.contains(original));
    assertNotEquals(
        "a start header lands in history, so it must be offloaded",
        original,
        sent.getValue().getHeader().getFieldsOrThrow("trace"));
  }

  @Test
  public void signalWithStartOffloadsHeaderPayloads() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.signalWithStart(any()))
        .thenReturn(SignalWithStartWorkflowExecutionResponse.getDefaultInstance());
    ArgumentCaptor<SignalWithStartWorkflowExecutionRequest> sent =
        ArgumentCaptor.forClass(SignalWithStartWorkflowExecutionRequest.class);

    invoker(rpc, driver)
        .signalWithStart(
            new WorkflowSignalWithStartInput(
                new WorkflowStartInput(
                    "wf-sws",
                    "MyWorkflowType",
                    headerWith("trace", "some-tracing-context"),
                    new Object[] {"argument"},
                    WorkflowOptions.newBuilder().setTaskQueue("tq").build()),
                "mySignal",
                new Object[] {"signal-arg"}));

    verify(rpc).signalWithStart(sent.capture());
    Payload original = tracePayload("some-tracing-context");
    assertTrue("the header value must reach the driver", driver.stored.contains(original));
    assertNotEquals(
        "the copied start header must carry the reference",
        original,
        sent.getValue().getHeader().getFieldsOrThrow("trace"));
  }

  @Test
  public void headerCodecRunsBeforeExternalStorage() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    PrefixPayloadCodec codec = new PrefixPayloadCodec();
    DataConverter converter =
        new CodecDataConverter(
            DefaultDataConverter.STANDARD_INSTANCE, Collections.singletonList(codec), false);
    Header header = Header.empty();
    header.putAll(Collections.singletonMap("trace", tracePayload("context")), true);
    invoker(rpc, converter, ExternalStorage.newBuilder().setDriver(driver))
        .signal(
            new WorkflowSignalInput(
                WorkflowExecution.newBuilder().setWorkflowId("wf").build(),
                "signal",
                header,
                new Object[0]));
    assertEquals(
        Collections.singletonList(
            codec.encode(Collections.singletonList(header.getValues().get("trace"))).get(0)),
        driver.stored);
  }

  @Test
  public void headerReferencesAreEncodedOnlyWhereTheProducerOptedIn() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    ArgumentCaptor<SignalWorkflowExecutionRequest> sent =
        ArgumentCaptor.forClass(SignalWorkflowExecutionRequest.class);
    Header header = Header.empty();
    header.putAll(Collections.singletonMap("opted-in", tracePayload("a")), true);
    header.getValues().put("plain", tracePayload("b"));

    encodingInvoker(rpc, driver)
        .signal(
            new WorkflowSignalInput(
                WorkflowExecution.newBuilder().setWorkflowId("wf").build(),
                "signal",
                header,
                new Object[0]));

    verify(rpc).signal(sent.capture());
    assertEncodedReference(sent.getValue().getHeader().getFieldsOrThrow("opted-in"));
    assertPlainReference(sent.getValue().getHeader().getFieldsOrThrow("plain"));
  }

  @Test
  public void startEncodesTheHeaderReferenceOfAnOptedInPropagator() {
    CapturingDriver driver = new CapturingDriver();
    GenericWorkflowClient rpc = mock(GenericWorkflowClient.class);
    when(rpc.start(any())).thenReturn(StartWorkflowExecutionResponse.getDefaultInstance());
    ArgumentCaptor<StartWorkflowExecutionRequest> sent =
        ArgumentCaptor.forClass(StartWorkflowExecutionRequest.class);
    ContextPropagator propagator = mock(ContextPropagator.class);
    when(propagator.encodeHeaders()).thenReturn(true);
    when(propagator.serializeContext(any()))
        .thenReturn(Collections.singletonMap("propagated", tracePayload("c")));

    encodingInvoker(rpc, driver)
        .start(
            new WorkflowStartInput(
                "wf",
                "MyWorkflowType",
                headerWith("plain", "d"),
                new Object[0],
                WorkflowOptions.newBuilder()
                    .setTaskQueue("tq")
                    .setContextPropagators(Collections.singletonList(propagator))
                    .build()));

    verify(rpc).start(sent.capture());
    assertEncodedReference(sent.getValue().getHeader().getFieldsOrThrow("propagated"));
    assertPlainReference(sent.getValue().getHeader().getFieldsOrThrow("plain"));
  }

  /** An invoker whose codec prefixes payloads and whose storage encodes references with it. */
  private static RootWorkflowClientInvoker encodingInvoker(
      GenericWorkflowClient rpc, StorageDriver driver) {
    return invoker(
        rpc,
        new CodecDataConverter(
            DefaultDataConverter.STANDARD_INSTANCE,
            Collections.singletonList(new PrefixPayloadCodec()),
            false),
        encodingReferences(ExternalStorage.newBuilder().setDriver(driver)));
  }

  private static void assertEncodedReference(Payload payload) {
    assertTrue(payload.containsMetadata(REFERENCE_MARKER));
    assertTrue(payload.getData().startsWith(PrefixPayloadCodec.PREFIX));
  }

  private static void assertPlainReference(Payload payload) {
    assertFalse(payload.containsMetadata(REFERENCE_MARKER));
    assertEquals("json/protobuf", payload.getMetadataOrThrow("encoding").toStringUtf8());
    assertFalse(payload.getData().startsWith(PrefixPayloadCodec.PREFIX));
  }

  private static Header headerWith(String key, String value) {
    return new Header(Collections.singletonMap(key, tracePayload(value)));
  }

  private static Payload tracePayload(String value) {
    return WorkflowClientOptions.newBuilder()
        .validateAndBuildWithDefaults()
        .getDataConverter()
        .toPayload(value)
        .get();
  }

  private static RootWorkflowClientInvoker invoker(
      GenericWorkflowClient rpc, StorageDriver driver) {
    return invoker(
        rpc,
        DefaultDataConverter.STANDARD_INSTANCE,
        ExternalStorage.newBuilder().setDriver(driver));
  }

  private static RootWorkflowClientInvoker invoker(
      GenericWorkflowClient rpc, DataConverter converter, ExternalStorage.Builder storage) {
    WorkflowClientOptions options =
        WorkflowClientOptions.newBuilder()
            .setNamespace(NAMESPACE)
            .setDataConverter(converter)
            .validateAndBuildWithDefaults();
    return new RootWorkflowClientInvoker(
        rpc,
        options.getNamespace(),
        options.getIdentity(),
        options.getQueryRejectCondition(),
        options.getContextPropagators(),
        options.getDataConverter(),
        new WorkerFactoryRegistry(),
        ExternalStorageRunner.create(storage.setPayloadSizeThreshold(0).build(), NAMESPACE));
  }

  private static final class CapturingDriver implements StorageDriver {
    volatile StorageDriverTargetInfo lastTarget;
    final List<Payload> stored = Collections.synchronizedList(new ArrayList<>());
    private int counter = 0;

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public String getType() {
      return "test.capturing";
    }

    @Override
    public synchronized CompletableFuture<List<StorageDriverClaim>> store(
        StorageDriverStoreContext context, List<Payload> payloads) {
      lastTarget = context.getTarget();
      stored.addAll(payloads);
      List<StorageDriverClaim> claims = new ArrayList<>();
      for (int i = 0; i < payloads.size(); i++) {
        claims.add(new StorageDriverClaim(Collections.singletonMap("key", "k-" + (counter++))));
      }
      return CompletableFuture.completedFuture(claims);
    }

    @Override
    public CompletableFuture<List<Payload>> retrieve(
        StorageDriverRetrieveContext context, List<StorageDriverClaim> claims) {
      throw new UnsupportedOperationException();
    }
  }
}
