package io.temporal.internal.testservice;

import com.google.protobuf.MessageLite;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Generates an in-process service and the channel for it. Useful for testing, usually with mock and
 * spy services.
 */
// TODO move to temporal-testing or temporal-test-server modules after WorkflowServiceStubs cleanup
public class InProcessGRPCServer {
  private final Server server;
  @Nullable private final ManagedChannel channel;

  /**
   * Register the passed services in a new in-memory gRPC service with health service providing
   * SERVING status for all of them. Also provides a channel to access the created server, see
   * {@link #getChannel()}
   *
   * @param services implementations of the gRPC services. For example, one of them can be an
   *     instance extending {@link WorkflowServiceGrpc.WorkflowServiceImplBase}
   */
  public InProcessGRPCServer(Collection<BindableService> services) {
    this(services, true);
  }

  /**
   * Register the passed services in a new in-memory gRPC service with health service providing
   * SERVING status for all of them.
   *
   * @param services implementations of the gRPC services. For example, one of them can be an
   *     instance extending {@link WorkflowServiceGrpc.WorkflowServiceImplBase}
   * @param createChannel defines if a channel to access the created server should be created. If
   *     true, the created channel will be accessible using {@link #getChannel()}
   */
  public InProcessGRPCServer(Collection<BindableService> services, boolean createChannel) {
    String serverName = InProcessServerBuilder.generateName();
    try {
      InProcessServerBuilder inProcessServerBuilder = InProcessServerBuilder.forName(serverName);
      GRPCServerHelper.registerServicesAndHealthChecks(
          services, inProcessServerBuilder, Collections.singletonList(new MessageSizeChecker()));
      server = inProcessServerBuilder.build().start();
    } catch (IOException unexpected) {
      throw new RuntimeException(unexpected);
    }
    channel =
        createChannel ? InProcessChannelBuilder.forName(serverName).directExecutor().build() : null;
  }

  public void shutdown() {
    if (channel != null) {
      channel.shutdown();
    }
    server.shutdown();
  }

  public void shutdownNow() {
    if (channel != null) {
      channel.shutdownNow();
    }
    server.shutdownNow();
  }

  public boolean isShutdown() {
    return (channel == null || channel.isShutdown()) && server.isShutdown();
  }

  public boolean isTerminated() {
    return (channel == null || channel.isTerminated()) && server.isTerminated();
  }

  public boolean awaitTermination(long timeout, TimeUnit unit) {
    long start = System.currentTimeMillis();
    long deadline = start + unit.toMillis(timeout);
    long left = deadline - System.currentTimeMillis();
    try {
      if (channel != null && !channel.awaitTermination(left, TimeUnit.MILLISECONDS)) {
        return false;
      }

      left = deadline - System.currentTimeMillis();
      return server.awaitTermination(left, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  public Server getServer() {
    return server;
  }

  @Nullable
  public ManagedChannel getChannel() {
    return channel;
  }

  /**
   * This interceptor is needed for testing RESOURCE_EXHAUSTED error handling because in-process
   * gRPC server doesn't check and cannot be configured to check message size.
   */
  public static class MessageSizeChecker implements ServerInterceptor {
    private final int maxMessageSize;

    public MessageSizeChecker() {
      this(4 * 1024 * 1024); // matching gRPC's default 4MB
    }

    public MessageSizeChecker(int maxMessageSize) {
      this.maxMessageSize = maxMessageSize;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      call.request(1);
      return new Listener<>(call, headers, next);
    }

    private class Listener<ReqT, RespT> extends ForwardingServerCallListener<ReqT> {
      private final ServerCall<ReqT, RespT> call;
      private final Metadata headers;
      private final ServerCallHandler<ReqT, RespT> next;
      private ServerCall.Listener<ReqT> delegate;
      private boolean delegateSet;

      public Listener(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        this.call = call;
        this.headers = headers;
        this.next = next;
        delegate = new ServerCall.Listener<ReqT>() {};
        delegateSet = false;
      }

      @Override
      protected ServerCall.Listener<ReqT> delegate() {
        return delegate;
      }

      @Override
      public void onMessage(ReqT message) {
        int size = ((MessageLite) message).getSerializedSize();
        if (size > maxMessageSize) {
          call.close(
              Status.RESOURCE_EXHAUSTED.withDescription(
                  String.format(
                      "grpc: received message larger than max (%d vs. %d)", size, maxMessageSize)),
              new Metadata());
        } else {
          if (!delegateSet) {
            delegateSet = true;
            delegate = next.startCall(call, headers);
          }
          super.onMessage(message);
        }
      }
    }
  }
}
