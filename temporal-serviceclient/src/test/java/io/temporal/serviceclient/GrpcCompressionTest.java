package io.temporal.serviceclient;

import static org.junit.Assert.*;

import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

public class GrpcCompressionTest {
  private static final Metadata.Key<String> GRPC_ENCODING =
      Metadata.Key.of("grpc-encoding", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> GRPC_ACCEPT_ENCODING =
      Metadata.Key.of("grpc-accept-encoding", Metadata.ASCII_STRING_MARSHALLER);

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

  private Metadata callGetSystemInfo(GrpcCompression compression) throws Exception {
    AtomicReference<Metadata> capturedHeaders = new AtomicReference<>();
    ServerInterceptor captureHeadersInterceptor =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedHeaders.set(headers);
            return next.startCall(call, headers);
          }
        };
    Server server =
        grpcCleanupRule.register(
            NettyServerBuilder.forPort(0)
                .addService(
                    ServerInterceptors.intercept(
                        new TestWorkflowService(), captureHeadersInterceptor))
                .build()
                .start());

    WorkflowServiceStubs serviceStubs =
        WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget("127.0.0.1:" + server.getPort())
                .setEnableHttps(false)
                .setGrpcCompression(compression)
                .build());
    try {
      serviceStubs.blockingStub().getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
    } finally {
      serviceStubs.shutdownNow();
    }

    assertNotNull(capturedHeaders.get());
    return capturedHeaders.get();
  }

  private static final class TestWorkflowService
      extends WorkflowServiceGrpc.WorkflowServiceImplBase {
    @Override
    public void getSystemInfo(
        GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> responseObserver) {
      responseObserver.onNext(GetSystemInfoResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }
}
