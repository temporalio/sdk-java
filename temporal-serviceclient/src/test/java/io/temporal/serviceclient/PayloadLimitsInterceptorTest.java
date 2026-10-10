package io.temporal.serviceclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.ByteString;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
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
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc.WorkflowServiceImplBase;
import io.temporal.internal.payload.limits.PayloadErrorLimits;
import io.temporal.internal.payload.limits.PayloadLimitViolation;
import io.temporal.internal.payload.limits.PayloadLimitViolationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class PayloadLimitsInterceptorTest {

  private static final PayloadErrorLimits ERROR_LIMITS = new PayloadErrorLimits(100, 100);

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
  public void attachingNoErrorLimitsLeavesAStubUsable() {
    // A call option cannot hold null, so a worker with no limits must not set one.
    WorkflowServiceStubs stubs = stubs(PayloadLimitsOptions.getDefaultInstance());
    PayloadErrorLimits.attach(stubs.blockingStub(), null)
        .startWorkflowExecution(startWithInput(10));
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
