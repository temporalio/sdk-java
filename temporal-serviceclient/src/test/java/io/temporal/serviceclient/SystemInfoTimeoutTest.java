package io.temporal.serviceclient;

import static org.junit.Assert.assertEquals;

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.api.workflowservice.v1.GetSystemInfoResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;

public class SystemInfoTimeoutTest {

  private static final GetSystemInfoResponse.Capabilities CAPABILITIES =
      GetSystemInfoResponse.Capabilities.newBuilder().setInternalErrorDifferentiation(true).build();

  private static final GetSystemInfoResponse GET_SYSTEM_INFO_RESPONSE =
      GetSystemInfoResponse.newBuilder().setCapabilities(CAPABILITIES).build();

  private static final RpcRetryOptions RPC_RETRY_OPTIONS =
      RpcRetryOptions.newBuilder()
          .setInitialInterval(Duration.ofMillis(10))
          .setBackoffCoefficient(1.0)
          .setMaximumAttempts(3)
          .setExpiration(Duration.ofMillis(100))
          .validateBuildWithDefaults();

  @Rule public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
  private final AtomicInteger getSystemInfoCount = new AtomicInteger(0);
  private final AbstractQueue<Duration> getSystemInfoTimeout = new ArrayBlockingQueue<Duration>(10);

  private final WorkflowServiceGrpc.WorkflowServiceImplBase workflowImpl =
      new WorkflowServiceGrpc.WorkflowServiceImplBase() {
        @Override
        public void getSystemInfo(
            GetSystemInfoRequest request, StreamObserver<GetSystemInfoResponse> responseObserver) {
          Duration timeout = getSystemInfoTimeout.poll();
          if (timeout != null) {
            try {
              Thread.sleep(timeout.toMillis());
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          }
          getSystemInfoCount.getAndIncrement();
          responseObserver.onNext(GET_SYSTEM_INFO_RESPONSE);
          responseObserver.onCompleted();
        }
      };

  private ManagedChannel managedChannel;

  @Before
  public void setUp() throws Exception {
    getSystemInfoCount.set(0);
    String serverName = InProcessServerBuilder.generateName();
    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(workflowImpl)
            .build()
            .start());
    managedChannel =
        grpcCleanupRule.register(
            InProcessChannelBuilder.forName(serverName).directExecutor().build());
  }

  @Test
  public void testGetServerCapabilitiesTimeoutExceeded() {
    WorkflowServiceStubsOptions serviceStubsOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .setChannel(managedChannel)
            .setRpcRetryOptions(RPC_RETRY_OPTIONS)
            .setSystemInfoTimeout(Duration.ofSeconds(1))
            .validateAndBuildWithDefaults();

    ClientInterceptor deadlineInterceptor =
        new GrpcDeadlineInterceptor(
            serviceStubsOptions.getRpcTimeout(),
            serviceStubsOptions.getRpcLongPollTimeout(),
            serviceStubsOptions.getRpcQueryTimeout());

    ChannelManager channelManager =
        new ChannelManager(serviceStubsOptions, Collections.singletonList(deadlineInterceptor));

    getSystemInfoTimeout.add(Duration.ofSeconds(2));

    StatusRuntimeException sre =
        Assert.assertThrows(
            StatusRuntimeException.class, () -> channelManager.getServerCapabilities().get());
    assertEquals(Status.Code.DEADLINE_EXCEEDED, sre.getStatus().getCode());
  }

  @Test
  public void testGetServerCapabilitiesRetry() {
    WorkflowServiceStubsOptions serviceStubsOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .setChannel(managedChannel)
            .setRpcRetryOptions(RPC_RETRY_OPTIONS)
            .setRpcTimeout(Duration.ofMillis(500))
            .setSystemInfoTimeout(Duration.ofSeconds(5))
            .validateAndBuildWithDefaults();

    ClientInterceptor deadlineInterceptor =
        new GrpcDeadlineInterceptor(
            serviceStubsOptions.getRpcTimeout(),
            serviceStubsOptions.getRpcLongPollTimeout(),
            serviceStubsOptions.getRpcQueryTimeout());

    ChannelManager channelManager =
        new ChannelManager(serviceStubsOptions, Collections.singletonList(deadlineInterceptor));

    getSystemInfoTimeout.add(Duration.ofSeconds(1));
    getSystemInfoTimeout.add(Duration.ofSeconds(1));

    GetSystemInfoResponse.Capabilities capabilities = channelManager.getServerCapabilities().get();
    assertEquals(CAPABILITIES, capabilities);
    assertEquals(3, getSystemInfoCount.get());
  }

  @Test
  public void testGetServerCapabilitiesTimeout() {
    WorkflowServiceStubsOptions serviceStubsOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .setChannel(managedChannel)
            .setRpcRetryOptions(RPC_RETRY_OPTIONS)
            .setSystemInfoTimeout(Duration.ofSeconds(10))
            .validateAndBuildWithDefaults();

    ClientInterceptor deadlineInterceptor =
        new GrpcDeadlineInterceptor(
            serviceStubsOptions.getRpcTimeout(),
            serviceStubsOptions.getRpcLongPollTimeout(),
            serviceStubsOptions.getRpcQueryTimeout());

    ChannelManager channelManager =
        new ChannelManager(serviceStubsOptions, Collections.singletonList(deadlineInterceptor));

    getSystemInfoTimeout.add(Duration.ofSeconds(6));

    GetSystemInfoResponse.Capabilities capabilities = channelManager.getServerCapabilities().get();
    assertEquals(CAPABILITIES, capabilities);
    assertEquals(1, getSystemInfoCount.get());
  }
}
