/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.serviceclient;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.temporal.internal.Version;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: (vkoby) Add metrics. TODO: (vkoby) double-check auto-close. If tasks are still running, the
 * service should still be up when they complete.
 */
public class GrpcWorkflowServiceFactory implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(GrpcWorkflowServiceFactory.class);
  private static final String LOCALHOST = "127.0.0.1";
  private static final int DEFAULT_LOCAL_TEMPORAL_SERVER_PORT = 7233;
  /** Default RPC timeout used for all non long poll calls. */
  private static final long DEFAULT_RPC_TIMEOUT_MILLIS = 1000;
  /** Default RPC timeout used for all long poll calls. */
  private static final long DEFAULT_POLL_RPC_TIMEOUT_MILLIS = 121 * 1000;
  /** Default RPC timeout for QueryWorkflow */
  private static final long DEFAULT_QUERY_RPC_TIMEOUT_MILLIS = 10000;

  /** refers to the name of the gRPC header that contains the client library version */
  private static final Metadata.Key<String> LIBRARY_VERSION_HEADER_KEY =
      Metadata.Key.of("temporal-sdk-version", Metadata.ASCII_STRING_MARSHALLER);

  /** refers to the name of the gRPC header that contains the client feature version */
  private static final Metadata.Key<String> FEATURE_VERSION_HEADER_KEY =
      Metadata.Key.of("temporal-sdk-feature-version", Metadata.ASCII_STRING_MARSHALLER);

  /** refers to the name of the gRPC header that contains the client SDK name */
  private static final Metadata.Key<String> CLIENT_IMPL_HEADER_KEY =
      Metadata.Key.of("temporal-sdk-name", Metadata.ASCII_STRING_MARSHALLER);

  private static final String CLIENT_IMPL_HEADER_VALUE = "temporal-go";

  protected ServiceFactoryOptions options;
  protected ManagedChannel channel;
  protected WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub;
  protected WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub;

  /**
   * Creates Temporal client that connects to the local instance of the Temporal Service that
   * listens on a default port (7933).
   */
  public GrpcWorkflowServiceFactory() {
    this(
        Strings.isNullOrEmpty(System.getenv("TEMPORAL_SEEDS"))
            ? LOCALHOST
            : System.getenv("TEMPORAL_SEEDS"),
        DEFAULT_LOCAL_TEMPORAL_SERVER_PORT);
  }

  public GrpcWorkflowServiceFactory(ManagedChannel channel, ServiceFactoryOptions options) {
    this.channel = channel;
    this.options = options;
    ClientInterceptor timeoutInterceptor = newTimeoutInterceptor();
    ClientInterceptor tracingInterceptor = newTracingInterceptor();
    Metadata headers = new Metadata();
    headers.put(LIBRARY_VERSION_HEADER_KEY, Version.LIBRARY_VERSION);
    headers.put(FEATURE_VERSION_HEADER_KEY, Version.FEATURE_VERSION);
    headers.put(CLIENT_IMPL_HEADER_KEY, CLIENT_IMPL_HEADER_VALUE);
    blockingStub =
        MetadataUtils.attachHeaders(WorkflowServiceGrpc.newBlockingStub(channel), headers)
            .withInterceptors(timeoutInterceptor, tracingInterceptor);
    futureStub =
        MetadataUtils.attachHeaders(WorkflowServiceGrpc.newFutureStub(channel), headers)
            .withInterceptors(timeoutInterceptor, tracingInterceptor);
    log.info(String.format("Created GRPC client for channel: %s", channel));
  }

  public GrpcWorkflowServiceFactory(ManagedChannel channel) {
    this(channel, new ServiceFactoryOptions.Builder().build());
  }

  /**
   * Creates Temporal client that connects to the specified host and port using default options.
   *
   * @param host host to connect
   * @param port port to connect
   */
  public GrpcWorkflowServiceFactory(String host, int port) {
    this(host, port, new ServiceFactoryOptions.Builder().build());
  }

  /**
   * Creates Temporal client that connects to the specified host and port using specified options.
   *
   * @param host host to connect
   * @param port port to connect
   * @param options configuration options like rpc timeouts.
   */
  public GrpcWorkflowServiceFactory(String host, int port, ServiceFactoryOptions options) {
    this(
        ManagedChannelBuilder.forAddress(
                Preconditions.checkNotNull(host, "host must not be null"), validatePort(port))
            .usePlaintext()
            // TODO: add .defaultServiceConfig or .enableRetry here if custom retry policy is
            // desired
            .build(),
        options);
  }

  /**
   * Generates the client for an in-process service using an in-memory channel. Useful for testing,
   * usually with mock and spy services.
   */
  public GrpcWorkflowServiceFactory(WorkflowServiceGrpc.WorkflowServiceImplBase serviceImpl) {
    String serverName = InProcessServerBuilder.generateName();
    try {
      InProcessServerBuilder.forName(serverName)
          .directExecutor()
          .addService(serviceImpl)
          .build()
          .start();
    } catch (IOException unexpected) {
      throw new RuntimeException(unexpected);
    }
    this.channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    this.options = new ServiceFactoryOptions.Builder().build();
    ClientInterceptor timeoutInterceptor = newTimeoutInterceptor();
    blockingStub =
        WorkflowServiceGrpc.newBlockingStub(channel).withInterceptors(timeoutInterceptor);
    futureStub = WorkflowServiceGrpc.newFutureStub(channel).withInterceptors(timeoutInterceptor);

    log.info(String.format("Created GRPC client for channel: %s", channel));
  }

  private ClientInterceptor newTimeoutInterceptor() {
    return new ClientInterceptor() {

      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        long duration = options.getRpcTimeoutMillis();
        String name = method.getFullMethodName();
        if (name.equals("workflowservice.WorkflowService/GetWorkflowExecutionHistory")) {
          Deadline deadline = callOptions.getDeadline();
          if (deadline == null) {
            duration = options.getRpcLongPollTimeoutMillis();
          } else {
            duration = deadline.timeRemaining(TimeUnit.MILLISECONDS);
            if (duration > options.getRpcLongPollTimeoutMillis()) {
              duration = options.getRpcLongPollTimeoutMillis();
            }
          }
        } else if (name.equals("workflowservice.WorkflowService/PollForDecisionTask")
            || name.equals("workflowservice.WorkflowService/PollForActivityTask")) {
          duration = options.getRpcLongPollTimeoutMillis();
        } else if (name.equals("workflowservice.WorkflowService/QueryWorkflow")) {
          duration = options.getRpcQueryTimeoutMillis();
        }
        if (log.isTraceEnabled()) {
          log.trace("TimeoutInterceptor method=" + name + ", timeoutMs=" + duration);
        }
        return next.newCall(method, callOptions.withDeadlineAfter(duration, TimeUnit.MILLISECONDS));
      }
    };
  }

  private ClientInterceptor newTracingInterceptor() {
    return new ClientInterceptor() {

      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)) {
          @Override
          public void sendMessage(ReqT message) {
            log.trace("Invoking " + method.getFullMethodName() + "with input: " + message);
            super.sendMessage(message);
          }

          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            ClientCall.Listener<RespT> listener =
                new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(
                    responseListener) {
                  @Override
                  public void onMessage(RespT message) {
                    log.trace(
                        "Returned " + method.getFullMethodName() + " with output: " + message);
                    super.onMessage(message);
                  }
                };
            super.start(listener, headers);
          }
        };
      }
    };
  }

  /** @return Blocking (synchronous) stub that allows direct calls to service. */
  public WorkflowServiceGrpc.WorkflowServiceBlockingStub blockingStub() {
    return blockingStub;
  }

  /** @return Future (asynchronous) stub that allows direct calls to service. */
  public WorkflowServiceGrpc.WorkflowServiceFutureStub futureStub() {
    return futureStub;
  }

  /** Simple port validation */
  private static int validatePort(int port) {
    if (port < 0) {
      throw new IllegalArgumentException("0 or negative port");
    }
    return port;
  }
  /**
   * This closes the underlying channel. Channels are expensive to create, so if multiple instances
   * of this class are created and closed frequently, it may make sense to move to a shared channel
   * model. This implements the AutoCloseable interface, so this class can be used with
   * try-with-resources.
   */
  @Override
  public void close() {
    try {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ignore) {
      /* Safe to ignore. */
    }
  }

  // TODO (vkoby): Figure out why AutoValue didn't work and re-implement this
  public static class ServiceFactoryOptions {

    /** The tChannel timeout in milliseconds */
    private final long rpcTimeoutMillis;

    /** The tChannel timeout for long poll calls in milliseconds */
    private final long rpcLongPollTimeoutMillis;

    /** The tChannel timeout for query workflow call in milliseconds */
    private final long rpcQueryTimeoutMillis;

    /** Optional TChannel headers */
    private final Map<String, String> headers;

    private ServiceFactoryOptions(ServiceFactoryOptions.Builder builder) {

      this.rpcLongPollTimeoutMillis = builder.rpcLongPollTimeoutMillis;
      this.rpcQueryTimeoutMillis = builder.rpcQueryTimeoutMillis;
      this.rpcTimeoutMillis = builder.rpcTimeoutMillis;

      if (builder.headers != null) {
        this.headers = ImmutableMap.copyOf(builder.headers);
      } else {
        this.headers = ImmutableMap.of();
      }
    }

    /** @return Returns the rpc timeout value in millis. */
    public long getRpcTimeoutMillis() {
      return rpcTimeoutMillis;
    }

    /** @return Returns the rpc timout for long poll requests in millis. */
    public long getRpcLongPollTimeoutMillis() {
      return rpcLongPollTimeoutMillis;
    }

    /** @return Returns the rpc timout for query workflow requests in millis. */
    public long getRpcQueryTimeoutMillis() {
      return rpcQueryTimeoutMillis;
    }

    public Map<String, String> getHeaders() {
      return headers;
    }

    /**
     * Builder is the builder for ClientOptions.
     *
     * @author venkat
     */
    public static class Builder {
      private long rpcTimeoutMillis = DEFAULT_RPC_TIMEOUT_MILLIS;
      private long rpcLongPollTimeoutMillis = DEFAULT_POLL_RPC_TIMEOUT_MILLIS;
      public long rpcQueryTimeoutMillis = DEFAULT_QUERY_RPC_TIMEOUT_MILLIS;
      private Map<String, String> headers;

      /**
       * Sets the rpc timeout value for non query and non long poll calls. Default is 1000.
       *
       * @param timeoutMillis timeout, in millis.
       */
      public ServiceFactoryOptions.Builder setRpcTimeout(long timeoutMillis) {
        this.rpcTimeoutMillis = timeoutMillis;
        return this;
      }

      /**
       * Sets the rpc timeout value for the following long poll based operations:
       * PollForDecisionTask, PollForActivityTask, GetWorkflowExecutionHistory. Should never be
       * below 60000 as this is server side timeout for the long poll. Default is 61000.
       *
       * @param timeoutMillis timeout, in millis.
       */
      public ServiceFactoryOptions.Builder setRpcLongPollTimeout(long timeoutMillis) {
        this.rpcLongPollTimeoutMillis = timeoutMillis;
        return this;
      }

      /**
       * Sets the rpc timeout value for query calls. Default is 10000.
       *
       * @param timeoutMillis timeout, in millis.
       */
      public ServiceFactoryOptions.Builder setQueryRpcTimeout(long timeoutMillis) {
        this.rpcQueryTimeoutMillis = timeoutMillis;
        return this;
      }

      public ServiceFactoryOptions.Builder setHeaders(Map<String, String> headers) {
        this.headers = headers;
        return this;
      }

      /**
       * Builds and returns a ClientOptions object.
       *
       * @return ClientOptions object with the specified params.
       */
      public ServiceFactoryOptions build() {
        return new ServiceFactoryOptions(this);
      }
    }
  }
}
