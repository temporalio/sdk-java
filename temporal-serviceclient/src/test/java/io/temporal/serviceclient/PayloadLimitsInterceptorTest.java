package io.temporal.serviceclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.CapableOf;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.util.Duration;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.StartWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceImplBase;
import io.temporal.internal.payload.limits.PayloadErrorLimits;
import io.temporal.internal.payload.limits.PayloadLimitViolation;
import io.temporal.internal.payload.limits.PayloadLimitViolationException;
import io.temporal.internal.payload.limits.PayloadLimitsInterceptor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class PayloadLimitsInterceptorTest {

  private static final PayloadErrorLimits ERROR_LIMITS = new PayloadErrorLimits(100, 100);

  // The validator is package-private in another package, so its logger is named directly.
  private static final String PAYLOAD_LIMIT_VALIDATOR_LOGGER =
      "io.temporal.internal.payload.limits.PayloadLimitValidator";

  @Rule public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private final AtomicInteger startCount = new AtomicInteger();
  private final AtomicInteger describeCount = new AtomicInteger();

  private final WorkflowServiceImplBase workflowImpl =
      new WorkflowServiceImplBase() {
        @Override
        public void getSystemInfo(
            GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> observer) {
          observer.onNext(GetSystemInfoResponse.getDefaultInstance());
          observer.onCompleted();
        }

        @Override
        public void startWorkflowExecution(
            StartWorkflowExecutionRequest request,
            StreamObserver<StartWorkflowExecutionResponse> observer) {
          startCount.incrementAndGet();
          observer.onNext(StartWorkflowExecutionResponse.getDefaultInstance());
          observer.onCompleted();
        }

        @Override
        public void describeNamespace(
            DescribeNamespaceRequest request, StreamObserver<DescribeNamespaceResponse> observer) {
          describeCount.incrementAndGet();
          observer.onNext(DescribeNamespaceResponse.getDefaultInstance());
          observer.onCompleted();
        }
      };

  private ManagedChannel channel;

  @Before
  public void setUp() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(workflowImpl)
            .build()
            .start());
    channel =
        grpcCleanupRule.register(
            InProcessChannelBuilder.forName(serverName).directExecutor().build());
  }

  private WorkflowServiceStubs stubs(
      PayloadLimitsOptions payloadLimits, ClientInterceptor... interceptors) {
    WorkflowServiceStubsOptions.Builder options =
        WorkflowServiceStubsOptions.newBuilder()
            .setChannel(channel)
            .setPayloadLimits(payloadLimits);
    if (interceptors.length > 0) {
      options.setGrpcClientInterceptors(java.util.Arrays.asList(interceptors));
    }
    WorkflowServiceStubs stubs = WorkflowServiceStubs.newServiceStubs(options.build());
    return stubs;
  }

  private static StartWorkflowExecutionRequest startWithInput(int dataLen) {
    return StartWorkflowExecutionRequest.newBuilder()
        .setInput(
            Payloads.newBuilder()
                .addPayloads(
                    Payload.newBuilder().setData(ByteString.copyFrom(new byte[dataLen])).build()))
        .build();
  }

  private static PayloadLimitsOptions warnAt(long size) {
    return PayloadLimitsOptions.newBuilder()
        .setPayloadsWarnSize(size)
        .setMemoWarnSize(size)
        .build();
  }

  @Test
  public void oversizedRequestWithoutErrorLimitsIsSent() {
    WorkflowServiceStubs stubs = stubs(warnAt(10));
    stubs.blockingStub().startWorkflowExecution(startWithInput(1000));
    assertEquals(1, startCount.get());
  }

  @Test
  public void anOversizedRequestWithoutErrorLimitsIsLoggedAsAWarning() {
    LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    Logger validatorLog = loggerContext.getLogger(PAYLOAD_LIMIT_VALIDATOR_LOGGER);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.setContext(loggerContext);
    logs.start();
    validatorLog.addAppender(logs);
    try {
      stubs(warnAt(10)).blockingStub().startWorkflowExecution(startWithInput(1000));
    } finally {
      validatorLog.detachAppender(logs);
      logs.stop();
    }

    assertEquals(1, startCount.get());
    assertEquals(1, logs.list.size());
    ILoggingEvent event = logs.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertTrue(
        event.getFormattedMessage(),
        event
            .getFormattedMessage()
            .startsWith(
                "[TMPRL1103] Attempted to upload payloads with size that exceeded the warning"
                    + " limit."));
  }

  @Test
  public void errorLimitsRejectOversizedRequestWithoutSendingIt() {
    WorkflowServiceStubs stubs = stubs(warnAt(10));
    StatusRuntimeException e =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                stubs
                    .blockingStub()
                    .withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, ERROR_LIMITS)
                    .startWorkflowExecution(startWithInput(1000)));

    assertEquals(Status.Code.INVALID_ARGUMENT, e.getStatus().getCode());
    assertEquals(
        "[TMPRL1103] Attempted to upload payloads with size that exceeded the error limit.",
        e.getStatus().getDescription());
    PayloadLimitViolation violation = PayloadLimitViolationException.find(e).get();
    assertEquals("input", violation.getPath());
    assertEquals(100, violation.getLimit());
    assertEquals(0, startCount.get());
  }

  @Test
  public void futureStubRejectsOversizedRequest() {
    WorkflowServiceStubs stubs = stubs(PayloadLimitsOptions.getDefaultInstance());
    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () ->
                stubs
                    .futureStub()
                    .withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, ERROR_LIMITS)
                    .startWorkflowExecution(startWithInput(1000))
                    .get());
    assertTrue(PayloadLimitViolationException.find(e).isPresent());
    assertEquals(0, startCount.get());
  }

  @Test
  public void errorLimitsAllowRequestUnderLimit() {
    WorkflowServiceStubs stubs = stubs(PayloadLimitsOptions.getDefaultInstance());
    stubs
        .blockingStub()
        .withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, ERROR_LIMITS)
        .startWorkflowExecution(startWithInput(10));
    assertEquals(1, startCount.get());
  }

  @Test
  public void noErrorLimitsSendAnOversizedRequest() {
    WorkflowServiceStubs stubs = stubs(PayloadLimitsOptions.getDefaultInstance());
    stubs
        .blockingStub()
        .withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, PayloadErrorLimits.NONE)
        .startWorkflowExecution(startWithInput(1000));
    assertEquals(1, startCount.get());
  }

  @Test
  public void requestWithoutPayloadFieldsIsUnaffected() {
    WorkflowServiceStubs stubs = stubs(warnAt(1));
    stubs
        .blockingStub()
        .withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, new PayloadErrorLimits(1, 1))
        .describeNamespace(DescribeNamespaceRequest.newBuilder().setNamespace("ns").build());
    assertEquals(1, describeCount.get());
  }

  @Test
  public void requestChangedByUserInterceptorIsChecked() {
    ClientInterceptor enlargingInterceptor =
        new ClientInterceptor() {
          @Override
          public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
              MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                next.newCall(method, callOptions)) {
              @Override
              @SuppressWarnings("unchecked")
              public void sendMessage(ReqT message) {
                if (message instanceof StartWorkflowExecutionRequest) {
                  message = (ReqT) startWithInput(1000);
                }
                super.sendMessage(message);
              }
            };
          }
        };
    WorkflowServiceStubs stubs =
        stubs(PayloadLimitsOptions.getDefaultInstance(), enlargingInterceptor);

    assertThrows(
        StatusRuntimeException.class,
        () ->
            stubs
                .blockingStub()
                .withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, ERROR_LIMITS)
                .startWorkflowExecution(startWithInput(10)));
    assertEquals(0, startCount.get());
  }

  @Test
  public void aRejectedRequestIsNotCountedAsARequest() throws Exception {
    CountingReporter reporter = new CountingReporter();
    Scope scope = new RootScopeBuilder().reporter(reporter).reportEvery(Duration.ofSeconds(1));
    WorkflowServiceStubs stubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setChannel(channel)
                .setMetricsScope(scope)
                .build());

    assertThrows(
        StatusRuntimeException.class,
        () ->
            stubs
                .blockingStub()
                .withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, ERROR_LIMITS)
                .startWorkflowExecution(startWithInput(1000)));
    stubs
        .blockingStub()
        .withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, ERROR_LIMITS)
        .startWorkflowExecution(startWithInput(10));
    scope.close();

    assertEquals(
        "only the request that was sent is counted",
        1,
        reporter.count(MetricsType.TEMPORAL_REQUEST, "StartWorkflowExecution"));
  }

  @Test
  public void aRejectedOrEarlyCancelledCallNeverReachesTheNextInterceptor() {
    Channel next = mock(Channel.class);
    PayloadLimitsInterceptor interceptor = new PayloadLimitsInterceptor(10, 10);
    CallOptions callOptions =
        CallOptions.DEFAULT.withOption(PayloadErrorLimits.CALL_OPTIONS_KEY, ERROR_LIMITS);

    ClientCall<StartWorkflowExecutionRequest, StartWorkflowExecutionResponse> rejected =
        interceptor.interceptCall(
            WorkflowServiceGrpc.getStartWorkflowExecutionMethod(), callOptions, next);
    @SuppressWarnings("unchecked")
    ClientCall.Listener<StartWorkflowExecutionResponse> rejectedListener =
        mock(ClientCall.Listener.class);
    rejected.start(rejectedListener, new Metadata());
    rejected.sendMessage(startWithInput(1000));
    rejected.halfClose();
    verify(rejectedListener)
        .onClose(argThat(status -> status.getCode() == Status.Code.INVALID_ARGUMENT), any());

    ClientCall<StartWorkflowExecutionRequest, StartWorkflowExecutionResponse> cancelled =
        interceptor.interceptCall(
            WorkflowServiceGrpc.getStartWorkflowExecutionMethod(), callOptions, next);
    @SuppressWarnings("unchecked")
    ClientCall.Listener<StartWorkflowExecutionResponse> cancelledListener =
        mock(ClientCall.Listener.class);
    cancelled.start(cancelledListener, new Metadata());
    cancelled.cancel("deadline", null);
    verify(cancelledListener)
        .onClose(argThat(status -> status.getCode() == Status.Code.CANCELLED), any());

    verify(next, never()).newCall(any(), any());
  }

  /** Records counter totals by metric name and operation. */
  @SuppressWarnings("deprecation") // StatsReporter's histogram methods take the deprecated Buckets.
  private static final class CountingReporter implements StatsReporter {
    private final Map<String, Long> counters = new ConcurrentHashMap<>();

    long count(String name, String operation) {
      return counters.getOrDefault(name + "|" + operation, 0L);
    }

    @Override
    public void reportCounter(String name, Map<String, String> tags, long value) {
      counters.merge(name + "|" + tags.get(MetricsTag.OPERATION_NAME), value, Long::sum);
    }

    @Override
    public void reportGauge(String name, Map<String, String> tags, double value) {}

    @Override
    public void reportTimer(
        String name, Map<String, String> tags, com.uber.m3.util.Duration interval) {}

    @Override
    public void reportHistogramValueSamples(
        String name,
        Map<String, String> tags,
        com.uber.m3.tally.Buckets buckets,
        double bucketLowerBound,
        double bucketUpperBound,
        long samples) {}

    @Override
    public void reportHistogramDurationSamples(
        String name,
        Map<String, String> tags,
        com.uber.m3.tally.Buckets buckets,
        com.uber.m3.util.Duration bucketLowerBound,
        com.uber.m3.util.Duration bucketUpperBound,
        long samples) {}

    @Override
    public Capabilities capabilities() {
      return CapableOf.REPORTING;
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  @Test
  public void payloadLimitsOptionsDefaults() {
    PayloadLimitsOptions defaults = PayloadLimitsOptions.getDefaultInstance();
    assertEquals(512 * 1024, defaults.getPayloadsWarnSize());
    assertEquals(2 * 1024, defaults.getMemoWarnSize());
    assertEquals(defaults, WorkflowServiceStubsOptions.getDefaultInstance().getPayloadLimits());
  }

  @Test
  public void payloadLimitsOptionsRejectNegativeSizes() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PayloadLimitsOptions.newBuilder().setPayloadsWarnSize(-1).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> PayloadLimitsOptions.newBuilder().setMemoWarnSize(-1).build());
  }

  @Test
  public void workflowServiceStubsOptionsCopyPayloadLimits() {
    WorkflowServiceStubsOptions options =
        WorkflowServiceStubsOptions.newBuilder().setPayloadLimits(warnAt(7)).build();
    WorkflowServiceStubsOptions copy = WorkflowServiceStubsOptions.newBuilder(options).build();
    assertEquals(warnAt(7), copy.getPayloadLimits());
    assertEquals(options, copy);
    assertNotEquals(options, WorkflowServiceStubsOptions.newBuilder().build());
  }
}
