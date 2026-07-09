package io.temporal.serviceclient;

import static org.junit.Assert.*;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.SignalWorkflowExecutionResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;

public class GrpcCompressionTest {
  private static final Metadata.Key<String> GRPC_ENCODING =
      Metadata.Key.of("grpc-encoding", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> GRPC_ACCEPT_ENCODING =
      Metadata.Key.of("grpc-accept-encoding", Metadata.ASCII_STRING_MARSHALLER);
  private static final Context.Key<String> REQUEST_COMPRESSION = Context.key("request-compression");

  @Rule public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  @Test
  public void gzipCompressionSendsAndAcceptsGzip() throws Exception {
    Metadata headers = callGetSystemInfo(GrpcCompression.GZIP);

    assertEquals("gzip", headers.get(GRPC_ENCODING));
    assertTrue(headers.get(GRPC_ACCEPT_ENCODING).contains("gzip"));
  }

  @Test
  public void noneCompressionDoesNotSendGzipButStillAcceptsGzip() throws Exception {
    Metadata headers = callGetSystemInfo(GrpcCompression.NONE);

    assertNull(headers.get(GRPC_ENCODING));
    assertTrue(headers.get(GRPC_ACCEPT_ENCODING).contains("gzip"));
  }

  @Test
  public void gzipCompressionDowngradesUnsupportedMethod() throws Exception {
    TestWorkflowService service = new TestWorkflowService();
    service.rejectGzipGetSystemInfo = true;
    CompressionHistoryInterceptor compressionHistory = new CompressionHistoryInterceptor();
    Server server = startServer(service, compressionHistory);
    WorkflowServiceStubs serviceStubs = newServiceStubs(server, GrpcCompression.GZIP);
    try {
      serviceStubs.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
      List<String> getSystemInfoHistory =
          compressionHistory.compressionHistoryForMethod(getSystemInfoMethod());
      assertEquals(2, getSystemInfoHistory.size());
      assertEquals("gzip", getSystemInfoHistory.get(0));
      assertNull(getSystemInfoHistory.get(1));

      serviceStubs.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
      getSystemInfoHistory = compressionHistory.compressionHistoryForMethod(getSystemInfoMethod());
      assertEquals(3, getSystemInfoHistory.size());
      assertEquals(1, Collections.frequency(getSystemInfoHistory, "gzip"));
      assertNull(getSystemInfoHistory.get(2));

      serviceStubs
          .blockingStub()
          .signalWorkflowExecution(
              SignalWorkflowExecutionRequest.newBuilder()
                  .setNamespace("test-namespace")
                  .setSignalName("test-signal")
                  .build());
      assertEquals(
          "gzip", compressionHistory.lastCompressionForMethod(signalWorkflowExecutionMethod()));
    } finally {
      serviceStubs.shutdownNow();
    }
  }

  @Test
  public void gzipCompressionDowngradesCompressionErrorWithoutEncodingName() throws Exception {
    TestWorkflowService service = new TestWorkflowService();
    service.rejectGzipGetSystemInfo = true;
    service.rejectGzipGetSystemInfoDescription = "grpc: Decompressor is not installed";
    CompressionHistoryInterceptor compressionHistory = new CompressionHistoryInterceptor();
    Server server = startServer(service, compressionHistory);
    WorkflowServiceStubs serviceStubs = newServiceStubs(server, GrpcCompression.GZIP);
    try {
      serviceStubs.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
    } finally {
      serviceStubs.shutdownNow();
    }

    List<String> getSystemInfoHistory =
        compressionHistory.compressionHistoryForMethod(getSystemInfoMethod());
    assertEquals(2, getSystemInfoHistory.size());
    assertEquals("gzip", getSystemInfoHistory.get(0));
    assertNull(getSystemInfoHistory.get(1));
  }

  @Test
  public void noneCompressionDoesNotInstallDowngrade() throws Exception {
    TestWorkflowService service = new TestWorkflowService();
    service.rejectGzipGetSystemInfo = true;
    CompressionHistoryInterceptor compressionHistory = new CompressionHistoryInterceptor();
    Server server = startServer(service, compressionHistory);
    WorkflowServiceStubs serviceStubs = newServiceStubs(server, GrpcCompression.NONE);
    try {
      serviceStubs.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
      List<String> getSystemInfoHistory =
          compressionHistory.compressionHistoryForMethod(getSystemInfoMethod());
      assertEquals(1, getSystemInfoHistory.size());
      assertNull(getSystemInfoHistory.get(0));
    } finally {
      serviceStubs.shutdownNow();
    }
  }

  @Test
  public void gzipCompressionDoesNotDowngradeGenericUnimplemented() throws Exception {
    TestWorkflowService service = new TestWorkflowService();
    service.getSystemInfoError =
        Status.UNIMPLEMENTED.withDescription("gzip feature is not implemented");
    CompressionHistoryInterceptor compressionHistory = new CompressionHistoryInterceptor();
    Server server = startServer(service, compressionHistory);
    WorkflowServiceStubs serviceStubs = newServiceStubs(server, GrpcCompression.GZIP);
    try {
      serviceStubs.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
      fail("expected StatusRuntimeException");
    } catch (StatusRuntimeException e) {
      assertEquals(Status.Code.UNIMPLEMENTED, e.getStatus().getCode());
    } finally {
      serviceStubs.shutdownNow();
    }
    List<String> getSystemInfoHistory =
        compressionHistory.compressionHistoryForMethod(getSystemInfoMethod());
    assertEquals(1, getSystemInfoHistory.size());
    assertEquals("gzip", getSystemInfoHistory.get(0));
  }

  private Metadata callGetSystemInfo(GrpcCompression compression) throws Exception {
    CompressionHistoryInterceptor compressionHistory = new CompressionHistoryInterceptor();
    Server server = startServer(new TestWorkflowService(), compressionHistory);
    WorkflowServiceStubs serviceStubs = newServiceStubs(server, compression);
    try {
      serviceStubs.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
    } finally {
      serviceStubs.shutdownNow();
    }

    assertNotNull(compressionHistory.lastHeaders());
    return compressionHistory.lastHeaders();
  }

  private Server startServer(
      TestWorkflowService service, CompressionHistoryInterceptor compressionHistory)
      throws Exception {
    return grpcCleanupRule.register(
        NettyServerBuilder.forPort(0)
            .addService(ServerInterceptors.intercept(service, compressionHistory))
            .build()
            .start());
  }

  private WorkflowServiceStubs newServiceStubs(Server server, GrpcCompression compression) {
    return WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder()
            .setTarget("127.0.0.1:" + server.getPort())
            .setEnableHttps(false)
            .setGrpcCompression(compression)
            .build());
  }

  private static String getSystemInfoMethod() {
    return WorkflowServiceGrpc.getGetSystemInfoMethod().getFullMethodName();
  }

  private static String signalWorkflowExecutionMethod() {
    return WorkflowServiceGrpc.getSignalWorkflowExecutionMethod().getFullMethodName();
  }

  private static final class CompressionHistoryInterceptor implements ServerInterceptor {
    private final Map<String, List<String>> compressionHistoryByMethod = new HashMap<>();
    private Metadata lastHeaders;

    @Override
    public synchronized <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      String compression = headers.get(GRPC_ENCODING);
      String methodName = call.getMethodDescriptor().getFullMethodName();
      List<String> compressionHistory = compressionHistoryByMethod.get(methodName);
      if (compressionHistory == null) {
        compressionHistory = new ArrayList<>();
        compressionHistoryByMethod.put(methodName, compressionHistory);
      }
      compressionHistory.add(compression);
      lastHeaders = headers;
      return Contexts.interceptCall(
          Context.current().withValue(REQUEST_COMPRESSION, compression), call, headers, next);
    }

    synchronized Metadata lastHeaders() {
      return lastHeaders;
    }

    synchronized List<String> compressionHistoryForMethod(String methodName) {
      List<String> compressionHistory = compressionHistoryByMethod.get(methodName);
      return compressionHistory == null
          ? Collections.emptyList()
          : new ArrayList<>(compressionHistory);
    }

    synchronized String lastCompressionForMethod(String methodName) {
      List<String> compressionHistory = compressionHistoryByMethod.get(methodName);
      if (compressionHistory == null || compressionHistory.isEmpty()) {
        return null;
      }
      return compressionHistory.get(compressionHistory.size() - 1);
    }
  }

  private static final class TestWorkflowService
      extends WorkflowServiceGrpc.WorkflowServiceImplBase {
    private boolean rejectGzipGetSystemInfo;
    private String rejectGzipGetSystemInfoDescription =
        "grpc: Decompressor is not installed for grpc-encoding \"gzip\"";
    private Status getSystemInfoError;

    @Override
    public void getSystemInfo(
        GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> responseObserver) {
      if (rejectGzipGetSystemInfo && "gzip".equals(REQUEST_COMPRESSION.get())) {
        responseObserver.onError(
            Status.UNIMPLEMENTED
                .withDescription(rejectGzipGetSystemInfoDescription)
                .asRuntimeException());
        return;
      }
      if (getSystemInfoError != null) {
        responseObserver.onError(getSystemInfoError.asRuntimeException());
        return;
      }
      responseObserver.onNext(GetSystemInfoResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }

    @Override
    public void signalWorkflowExecution(
        SignalWorkflowExecutionRequest request,
        StreamObserver<SignalWorkflowExecutionResponse> responseObserver) {
      responseObserver.onNext(SignalWorkflowExecutionResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }
}
