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

package com.uber.cadence.serviceclient;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.uber.cadence.BadRequestError;
import com.uber.cadence.ClientVersionNotSupportedError;
import com.uber.cadence.CountWorkflowExecutionsRequest;
import com.uber.cadence.CountWorkflowExecutionsResponse;
import com.uber.cadence.DeprecateDomainRequest;
import com.uber.cadence.DescribeDomainRequest;
import com.uber.cadence.DescribeDomainResponse;
import com.uber.cadence.DescribeTaskListRequest;
import com.uber.cadence.DescribeTaskListResponse;
import com.uber.cadence.DescribeWorkflowExecutionRequest;
import com.uber.cadence.DescribeWorkflowExecutionResponse;
import com.uber.cadence.DomainAlreadyExistsError;
import com.uber.cadence.DomainNotActiveError;
import com.uber.cadence.EntityNotExistsError;
import com.uber.cadence.GetSearchAttributesResponse;
import com.uber.cadence.GetWorkflowExecutionHistoryRequest;
import com.uber.cadence.GetWorkflowExecutionHistoryResponse;
import com.uber.cadence.InternalServiceError;
import com.uber.cadence.LimitExceededError;
import com.uber.cadence.ListClosedWorkflowExecutionsRequest;
import com.uber.cadence.ListClosedWorkflowExecutionsResponse;
import com.uber.cadence.ListDomainsRequest;
import com.uber.cadence.ListDomainsResponse;
import com.uber.cadence.ListOpenWorkflowExecutionsRequest;
import com.uber.cadence.ListOpenWorkflowExecutionsResponse;
import com.uber.cadence.ListWorkflowExecutionsRequest;
import com.uber.cadence.ListWorkflowExecutionsResponse;
import com.uber.cadence.PollForActivityTaskRequest;
import com.uber.cadence.PollForActivityTaskResponse;
import com.uber.cadence.PollForDecisionTaskRequest;
import com.uber.cadence.PollForDecisionTaskResponse;
import com.uber.cadence.QueryFailedError;
import com.uber.cadence.QueryWorkflowRequest;
import com.uber.cadence.QueryWorkflowResponse;
import com.uber.cadence.RecordActivityTaskHeartbeatByIDRequest;
import com.uber.cadence.RecordActivityTaskHeartbeatRequest;
import com.uber.cadence.RecordActivityTaskHeartbeatResponse;
import com.uber.cadence.RegisterDomainRequest;
import com.uber.cadence.RequestCancelWorkflowExecutionRequest;
import com.uber.cadence.ResetStickyTaskListRequest;
import com.uber.cadence.ResetStickyTaskListResponse;
import com.uber.cadence.ResetWorkflowExecutionRequest;
import com.uber.cadence.ResetWorkflowExecutionResponse;
import com.uber.cadence.RespondActivityTaskCanceledByIDRequest;
import com.uber.cadence.RespondActivityTaskCanceledRequest;
import com.uber.cadence.RespondActivityTaskCompletedByIDRequest;
import com.uber.cadence.RespondActivityTaskCompletedRequest;
import com.uber.cadence.RespondActivityTaskFailedByIDRequest;
import com.uber.cadence.RespondActivityTaskFailedRequest;
import com.uber.cadence.RespondDecisionTaskCompletedRequest;
import com.uber.cadence.RespondDecisionTaskCompletedResponse;
import com.uber.cadence.RespondDecisionTaskFailedRequest;
import com.uber.cadence.RespondQueryTaskCompletedRequest;
import com.uber.cadence.ServiceBusyError;
import com.uber.cadence.SignalWithStartWorkflowExecutionRequest;
import com.uber.cadence.SignalWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionRequest;
import com.uber.cadence.StartWorkflowExecutionResponse;
import com.uber.cadence.TerminateWorkflowExecutionRequest;
import com.uber.cadence.UpdateDomainRequest;
import com.uber.cadence.UpdateDomainResponse;
import com.uber.cadence.WorkflowExecutionAlreadyStartedError;
import com.uber.cadence.WorkflowService;
import com.uber.cadence.WorkflowService.GetWorkflowExecutionHistory_result;
import com.uber.cadence.internal.Version;
import com.uber.cadence.internal.common.CheckedExceptionWrapper;
import com.uber.cadence.internal.metrics.MetricsType;
import com.uber.cadence.internal.metrics.NoopScope;
import com.uber.cadence.internal.metrics.ServiceMethod;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.tchannel.api.ResponseCode;
import com.uber.tchannel.api.SubChannel;
import com.uber.tchannel.api.TChannel;
import com.uber.tchannel.api.TFuture;
import com.uber.tchannel.api.errors.TChannelError;
import com.uber.tchannel.messages.ThriftRequest;
import com.uber.tchannel.messages.ThriftResponse;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkflowServiceTChannel implements IWorkflowService {

  private static final int DEFAULT_LOCAL_CADENCE_SERVER_PORT = 7933;

  private static final String LOCALHOST = "127.0.0.1";

  /** Default RPC timeout used for all non long poll calls. */
  private static final long DEFAULT_RPC_TIMEOUT_MILLIS = 1000;
  /** Default RPC timeout used for all long poll calls. */
  private static final long DEFAULT_POLL_RPC_TIMEOUT_MILLIS = 121 * 1000;

  /** Default RPC timeout for QueryWorkflow */
  private static final long DEFAULT_QUERY_RPC_TIMEOUT_MILLIS = 10000;

  private static final String DEFAULT_CLIENT_APP_NAME = "cadence-client";

  /** Name of the Cadence service front end as required by TChannel. */
  private static final String DEFAULT_SERVICE_NAME = "cadence-frontend";

  private static final Logger log = LoggerFactory.getLogger(WorkflowServiceTChannel.class);

  public static class ClientOptions {

    /** The tChannel timeout in milliseconds */
    private final long rpcTimeoutMillis;

    /** The tChannel timeout for long poll calls in milliseconds */
    private final long rpcLongPollTimeoutMillis;

    /** The tChannel timeout for query workflow call in milliseconds */
    private final long rpcQueryTimeoutMillis;

    /** TChannel service name that the Cadence service was started with. */
    private final String serviceName;

    /** Name of the service using the cadence-client. */
    private final String clientAppName;

    /** Client for metrics reporting. */
    private final Scope metricsScope;

    /** Optional TChannel transport headers */
    private final Map<String, String> transportHeaders;

    /** Optional TChannel headers */
    private final Map<String, String> headers;

    private ClientOptions(Builder builder) {
      this.rpcTimeoutMillis = builder.rpcTimeoutMillis;
      if (builder.clientAppName == null) {
        this.clientAppName = DEFAULT_CLIENT_APP_NAME;
      } else {
        this.clientAppName = builder.clientAppName;
      }
      if (builder.serviceName == null) {
        this.serviceName = DEFAULT_SERVICE_NAME;
      } else {
        this.serviceName = builder.serviceName;
      }
      this.rpcLongPollTimeoutMillis = builder.rpcLongPollTimeoutMillis;
      this.rpcQueryTimeoutMillis = builder.rpcQueryTimeoutMillis;
      if (builder.metricsScope == null) {
        builder.metricsScope = NoopScope.getInstance();
      }
      this.metricsScope = builder.metricsScope;
      if (builder.transportHeaders != null) {
        this.transportHeaders = ImmutableMap.copyOf(builder.transportHeaders);
      } else {
        this.transportHeaders = ImmutableMap.of();
      }

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

    /** Returns the client application name. */
    public String getClientAppName() {
      return this.clientAppName;
    }

    public String getServiceName() {
      return serviceName;
    }

    public Scope getMetricsScope() {
      return metricsScope;
    }

    public Map<String, String> getTransportHeaders() {
      return transportHeaders;
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

      private String clientAppName = DEFAULT_CLIENT_APP_NAME;
      //            private MetricsClient metricsClient = new DefaultMetricsClient();
      private long rpcTimeoutMillis = DEFAULT_RPC_TIMEOUT_MILLIS;
      private long rpcLongPollTimeoutMillis = DEFAULT_POLL_RPC_TIMEOUT_MILLIS;
      public long rpcQueryTimeoutMillis = DEFAULT_QUERY_RPC_TIMEOUT_MILLIS;
      public String serviceName;
      private Scope metricsScope;
      private Map<String, String> transportHeaders;
      private Map<String, String> headers;

      /**
       * Sets the rpc timeout value for non query and non long poll calls. Default is 1000.
       *
       * @param timeoutMillis timeout, in millis.
       */
      public Builder setRpcTimeout(long timeoutMillis) {
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
      public Builder setRpcLongPollTimeout(long timeoutMillis) {
        this.rpcLongPollTimeoutMillis = timeoutMillis;
        return this;
      }

      /**
       * Sets the rpc timeout value for query calls. Default is 10000.
       *
       * @param timeoutMillis timeout, in millis.
       */
      public Builder setQueryRpcTimeout(long timeoutMillis) {
        this.rpcQueryTimeoutMillis = timeoutMillis;
        return this;
      }

      /**
       * Sets the client application name.
       *
       * <p>This name will be used as the tchannel client service name. It will also be reported as
       * a tag along with metrics emitted to m3.
       *
       * @param clientAppName String representing the client application name.
       * @return Builder for ClentOptions
       */
      public Builder setClientAppName(String clientAppName) {
        this.clientAppName = clientAppName;
        return this;
      }

      /**
       * Sets the service name that Cadence service was started with.
       *
       * @param serviceName String representing the service name
       * @return Builder for ClentOptions
       */
      public Builder setServiceName(String serviceName) {
        this.serviceName = serviceName;
        return this;
      }

      /**
       * Sets the metrics scope to be used for metrics reporting.
       *
       * @param metricsScope
       * @return Builder for ClentOptions
       */
      public Builder setMetricsScope(Scope metricsScope) {
        this.metricsScope = metricsScope;
        return this;
      }

      /**
       * Sets additional transport headers for tchannel client.
       *
       * @param transportHeaders Map with additional transport headers
       * @return Builder for ClentOptions
       */
      public Builder setTransportHeaders(Map<String, String> transportHeaders) {
        this.transportHeaders = transportHeaders;
        return this;
      }

      public Builder setHeaders(Map<String, String> headers) {
        this.headers = headers;
        return this;
      }

      /**
       * Builds and returns a ClientOptions object.
       *
       * @return ClientOptions object with the specified params.
       */
      public ClientOptions build() {
        return new ClientOptions(this);
      }
    }
  }

  private static final String INTERFACE_NAME = "WorkflowService";

  private final ClientOptions options;
  private final Map<String, String> thriftHeaders;
  private final TChannel tChannel;
  private final SubChannel subChannel;

  /**
   * Creates Cadence client that connects to the local instance of the Cadence Service that listens
   * on a default port (7933).
   */
  public WorkflowServiceTChannel() {
    this(
        Strings.isNullOrEmpty(System.getenv("CADENCE_SEEDS"))
            ? LOCALHOST
            : System.getenv("CADENCE_SEEDS"),
        DEFAULT_LOCAL_CADENCE_SERVER_PORT,
        new ClientOptions.Builder().build());
  }

  /**
   * Creates Cadence client that connects to the specified host and port using default options.
   *
   * @param host host to connect
   * @param port port to connect
   */
  public WorkflowServiceTChannel(String host, int port) {
    this(host, port, new ClientOptions.Builder().build());
  }

  /**
   * Creates Cadence client that connects to the specified host and port using specified options.
   *
   * @param host host to connect
   * @param port port to connect
   * @param options configuration options like rpc timeouts.
   */
  public WorkflowServiceTChannel(String host, int port, ClientOptions options) {
    if (host == null) {
      throw new IllegalArgumentException("null host");
    }
    if (port <= 0) {
      throw new IllegalArgumentException("0 or negative port");
    }
    this.options = options;
    this.thriftHeaders = getThriftHeaders(options);
    // this.metricsReporter = new MetricsReporter(options.getMetricsClient());
    // Need to create tChannel last in order to prevent leaking when an exception is thrown
    this.tChannel = new TChannel.Builder(options.getClientAppName()).build();

    try {
      InetAddress address = InetAddress.getByName(host);
      ArrayList<InetSocketAddress> peers = new ArrayList<>();
      peers.add(new InetSocketAddress(address, port));
      this.subChannel = tChannel.makeSubChannel(options.getServiceName()).setPeers(peers);
      log.info(
          "Initialized TChannel for service "
              + this.subChannel.getServiceName()
              + ", LibraryVersion: "
              + Version.LIBRARY_VERSION
              + ", FeatureVersion: "
              + Version.FEATURE_VERSION);
    } catch (UnknownHostException e) {
      tChannel.shutdown();
      throw new RuntimeException("Unable to get name of host " + host, e);
    }
  }

  /**
   * Creates Cadence client with specified sub channel and options.
   *
   * @param subChannel sub channel for communicating with cadence frontend service.
   * @param options configuration options like rpc timeouts.
   */
  public WorkflowServiceTChannel(SubChannel subChannel, ClientOptions options) {
    this.options = options;
    this.thriftHeaders = getThriftHeaders(options);
    // this.metricsReporter = new MetricsReporter(options.getMetricsClient());
    this.tChannel = null;
    this.subChannel = subChannel;
  }

  private static Map<String, String> getThriftHeaders(ClientOptions options) {
    String envUserName = System.getProperty("user.name");
    String envHostname;
    try {
      envHostname = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      envHostname = "localhost";
    }

    ImmutableMap.Builder<String, String> builder =
        ImmutableMap.<String, String>builder()
            .put("user-name", envUserName)
            .put("host-name", envHostname)
            .put("cadence-client-library-version", Version.LIBRARY_VERSION)
            .put("cadence-client-feature-version", Version.FEATURE_VERSION)
            .put("cadence-client-name", "uber-java");

    if (options.headers != null) {
      for (Map.Entry<String, String> entry : options.headers.entrySet()) {
        builder.put(entry.getKey(), entry.getValue());
      }
    }

    return builder.build();
  }

  /** Returns the endpoint in the format service::method" */
  private static String getEndpoint(String service, String method) {
    return String.format("%s::%s", service, method);
  }

  private <T> ThriftRequest<T> buildThriftRequest(String apiName, T body) {
    return buildThriftRequest(apiName, body, null);
  }

  private <T> ThriftRequest<T> buildThriftRequest(String apiName, T body, Long rpcTimeoutOverride) {
    String endpoint = getEndpoint(INTERFACE_NAME, apiName);
    ThriftRequest.Builder<T> builder =
        new ThriftRequest.Builder<>(options.getServiceName(), endpoint);
    // Create a mutable hashmap for headers, as tchannel.tracing.PrefixedHeadersCarrier assumes
    // that it can call put directly to add new stuffs (e.g. traces).
    builder.setHeaders(new HashMap<>(thriftHeaders));
    if (rpcTimeoutOverride != null) {
      builder.setTimeout(rpcTimeoutOverride);
    } else {
      builder.setTimeout(this.options.getRpcTimeoutMillis());
    }
    for (Map.Entry<String, String> header : this.options.getTransportHeaders().entrySet()) {
      builder.setTransportHeader(header.getKey(), header.getValue());
    }
    builder.setBody(body);
    return builder.build();
  }

  private <T> ThriftResponse<T> doRemoteCall(ThriftRequest<?> request) throws TException {
    ThriftResponse<T> response = null;
    try {
      TFuture<ThriftResponse<T>> future = subChannel.send(request);
      response = future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TException(e);
    } catch (ExecutionException e) {
      throw new TException(e);
    } catch (TChannelError e) {
      throw new TException("Rpc error", e);
    }
    this.throwOnRpcError(response);
    return response;
  }

  private <T> CompletableFuture<ThriftResponse<T>> doRemoteCallAsync(ThriftRequest<?> request) {
    final CompletableFuture<ThriftResponse<T>> result = new CompletableFuture<>();
    TFuture<ThriftResponse<T>> future = null;
    try {
      future = subChannel.send(request);
    } catch (TChannelError tChannelError) {
      result.completeExceptionally(new TException(tChannelError));
    }
    future.addCallback(
        response -> {
          if (response.isError()) {
            result.completeExceptionally(new TException("Rpc error:" + response.getError()));
          } else {
            result.complete(response);
          }
        });
    return result;
  }

  private void throwOnRpcError(ThriftResponse<?> response) throws TException {
    if (response.isError()) {
      throw new TException("Rpc error:" + response.getError());
    }
  }

  @Override
  public void close() {
    if (tChannel != null) {
      tChannel.shutdown();
    }
  }

  interface RemoteCall<T> {
    T apply() throws TException;
  }

  private <T> T measureRemoteCall(String scopeName, RemoteCall<T> call) throws TException {
    Scope scope = options.getMetricsScope().subScope(scopeName);
    scope.counter(MetricsType.CADENCE_REQUEST).inc(1);
    Stopwatch sw = scope.timer(MetricsType.CADENCE_LATENCY).start();
    try {
      T resp = call.apply();
      sw.stop();
      return resp;
    } catch (EntityNotExistsError
        | BadRequestError
        | DomainAlreadyExistsError
        | WorkflowExecutionAlreadyStartedError
        | QueryFailedError e) {
      sw.stop();
      scope.counter(MetricsType.CADENCE_INVALID_REQUEST).inc(1);
      throw e;
    } catch (TException e) {
      sw.stop();
      scope.counter(MetricsType.CADENCE_ERROR).inc(1);
      throw e;
    }
  }

  interface RemoteProc {
    void apply() throws TException;
  }

  private void measureRemoteProc(String scopeName, RemoteProc proc) throws TException {
    measureRemoteCall(
        scopeName,
        () -> {
          proc.apply();
          return null;
        });
  }

  @Override
  public void RegisterDomain(RegisterDomainRequest request) throws TException {
    measureRemoteProc(ServiceMethod.REGISTER_DOMAIN, () -> registerDomain(request));
  }

  private void registerDomain(RegisterDomainRequest registerRequest) throws TException {
    ThriftResponse<WorkflowService.RegisterDomain_result> response = null;
    try {
      ThriftRequest<WorkflowService.RegisterDomain_args> request =
          buildThriftRequest(
              "RegisterDomain", new WorkflowService.RegisterDomain_args(registerRequest));
      response = doRemoteCall(request);
      WorkflowService.RegisterDomain_result result =
          response.getBody(WorkflowService.RegisterDomain_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetDomainExistsError()) {
        throw result.getDomainExistsError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      throw new TException("RegisterDomain failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public DescribeDomainResponse DescribeDomain(DescribeDomainRequest describeRequest)
      throws TException {
    return measureRemoteCall(ServiceMethod.DESCRIBE_DOMAIN, () -> describeDomain(describeRequest));
  }

  private DescribeDomainResponse describeDomain(DescribeDomainRequest describeRequest)
      throws TException {
    ThriftResponse<WorkflowService.DescribeDomain_result> response = null;
    try {
      ThriftRequest<WorkflowService.DescribeDomain_args> request =
          buildThriftRequest(
              "DescribeDomain", new WorkflowService.DescribeDomain_args(describeRequest));
      response = doRemoteCall(request);
      WorkflowService.DescribeDomain_result result =
          response.getBody(WorkflowService.DescribeDomain_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      throw new TException("DescribeDomain failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public ListDomainsResponse ListDomains(ListDomainsRequest listRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          TException {
    return measureRemoteCall(ServiceMethod.LIST_DOMAINS, () -> listDomains(listRequest));
  }

  private ListDomainsResponse listDomains(ListDomainsRequest describeRequest) throws TException {
    ThriftResponse<WorkflowService.ListDomains_result> response = null;
    try {
      ThriftRequest<WorkflowService.ListDomains_args> request =
          buildThriftRequest("ListDomains", new WorkflowService.ListDomains_args(describeRequest));
      response = doRemoteCall(request);
      WorkflowService.ListDomains_result result =
          response.getBody(WorkflowService.ListDomains_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      throw new TException("ListDomains failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public UpdateDomainResponse UpdateDomain(UpdateDomainRequest updateRequest) throws TException {
    return measureRemoteCall(ServiceMethod.UPDATE_DOMAIN, () -> updateDomain(updateRequest));
  }

  private UpdateDomainResponse updateDomain(UpdateDomainRequest updateRequest) throws TException {
    ThriftResponse<WorkflowService.UpdateDomain_result> response = null;
    try {
      ThriftRequest<WorkflowService.UpdateDomain_args> request =
          buildThriftRequest("UpdateDomain", new WorkflowService.UpdateDomain_args(updateRequest));
      response = doRemoteCall(request);
      WorkflowService.UpdateDomain_result result =
          response.getBody(WorkflowService.UpdateDomain_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      throw new TException("UpdateDomain failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void DeprecateDomain(DeprecateDomainRequest deprecateRequest) throws TException {
    measureRemoteProc(ServiceMethod.DEPRECATE_DOMAIN, () -> deprecateDomain(deprecateRequest));
  }

  private void deprecateDomain(DeprecateDomainRequest deprecateRequest) throws TException {
    ThriftResponse<WorkflowService.DeprecateDomain_result> response = null;
    try {
      ThriftRequest<WorkflowService.DeprecateDomain_args> request =
          buildThriftRequest(
              "DeprecateDomain", new WorkflowService.DeprecateDomain_args(deprecateRequest));
      response = doRemoteCall(request);
      WorkflowService.DeprecateDomain_result result =
          response.getBody(WorkflowService.DeprecateDomain_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      throw new TException("DeprecateDomain failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public StartWorkflowExecutionResponse StartWorkflowExecution(
      StartWorkflowExecutionRequest request) throws TException {
    return measureRemoteCall(
        ServiceMethod.START_WORKFLOW_EXECUTION, () -> startWorkflowExecution(request));
  }

  private StartWorkflowExecutionResponse startWorkflowExecution(
      StartWorkflowExecutionRequest startRequest) throws TException {
    startRequest.setRequestId(UUID.randomUUID().toString());
    ThriftResponse<WorkflowService.StartWorkflowExecution_result> response = null;
    try {
      ThriftRequest<WorkflowService.StartWorkflowExecution_args> request =
          buildThriftRequest(
              "StartWorkflowExecution",
              new WorkflowService.StartWorkflowExecution_args(startRequest));
      response = doRemoteCall(request);
      WorkflowService.StartWorkflowExecution_result result =
          response.getBody(WorkflowService.StartWorkflowExecution_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetSessionAlreadyExistError()) {
        throw result.getSessionAlreadyExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      throw new TException("StartWorkflowExecution failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public GetWorkflowExecutionHistoryResponse GetWorkflowExecutionHistory(
      GetWorkflowExecutionHistoryRequest request) throws TException {
    return measureRemoteCall(
        ServiceMethod.GET_WORKFLOW_EXECUTION_HISTORY, () -> getWorkflowExecutionHistory(request));
  }

  private GetWorkflowExecutionHistoryResponse getWorkflowExecutionHistory(
      GetWorkflowExecutionHistoryRequest getRequest) throws TException {
    ThriftResponse<WorkflowService.GetWorkflowExecutionHistory_result> response = null;
    try {
      ThriftRequest<WorkflowService.GetWorkflowExecutionHistory_args> request;
      if (getRequest.isWaitForNewEvent()) {
        request =
            buildThriftRequest(
                "GetWorkflowExecutionHistory",
                new WorkflowService.GetWorkflowExecutionHistory_args(getRequest),
                options.getRpcLongPollTimeoutMillis());
      } else {
        request =
            buildThriftRequest(
                "GetWorkflowExecutionHistory",
                new WorkflowService.GetWorkflowExecutionHistory_args(getRequest));
      }

      response = doRemoteCall(request);
      WorkflowService.GetWorkflowExecutionHistory_result result =
          response.getBody(WorkflowService.GetWorkflowExecutionHistory_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      throw new TException("GetWorkflowExecutionHistory failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public PollForDecisionTaskResponse PollForDecisionTask(PollForDecisionTaskRequest request)
      throws TException {
    return measureRemoteCall(
        ServiceMethod.POLL_FOR_DECISION_TASK, () -> pollForDecisionTask(request));
  }

  private PollForDecisionTaskResponse pollForDecisionTask(PollForDecisionTaskRequest pollRequest)
      throws TException {
    ThriftResponse<WorkflowService.PollForDecisionTask_result> response = null;
    try {
      ThriftRequest<WorkflowService.PollForDecisionTask_args> request =
          buildThriftRequest(
              "PollForDecisionTask",
              new WorkflowService.PollForDecisionTask_args(pollRequest),
              options.getRpcLongPollTimeoutMillis());
      response = doRemoteCall(request);
      WorkflowService.PollForDecisionTask_result result =
          response.getBody(WorkflowService.PollForDecisionTask_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      throw new TException("PollForDecisionTask failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public RespondDecisionTaskCompletedResponse RespondDecisionTaskCompleted(
      RespondDecisionTaskCompletedRequest completedRequest) throws TException {
    return measureRemoteCall(
        ServiceMethod.RESPOND_DECISION_TASK_COMPLETED,
        () -> respondDecisionTaskCompleted(completedRequest));
  }

  private RespondDecisionTaskCompletedResponse respondDecisionTaskCompleted(
      RespondDecisionTaskCompletedRequest completedRequest) throws TException {
    ThriftResponse<WorkflowService.RespondDecisionTaskCompleted_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondDecisionTaskCompleted_args> request =
          buildThriftRequest(
              "RespondDecisionTaskCompleted",
              new WorkflowService.RespondDecisionTaskCompleted_args(completedRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondDecisionTaskCompleted_result result =
          response.getBody(WorkflowService.RespondDecisionTaskCompleted_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      throw new TException("RespondDecisionTaskCompleted failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RespondDecisionTaskFailed(RespondDecisionTaskFailedRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.RESPOND_DECISION_TASK_FAILED, () -> respondDecisionTaskFailed(request));
  }

  private void respondDecisionTaskFailed(RespondDecisionTaskFailedRequest failedRequest)
      throws TException {
    ThriftResponse<WorkflowService.RespondDecisionTaskFailed_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondDecisionTaskFailed_args> request =
          buildThriftRequest(
              "RespondDecisionTaskFailed",
              new WorkflowService.RespondDecisionTaskFailed_args(failedRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondDecisionTaskFailed_result result =
          response.getBody(WorkflowService.RespondDecisionTaskFailed_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      throw new TException("RespondDecisionTaskFailed failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public PollForActivityTaskResponse PollForActivityTask(PollForActivityTaskRequest request)
      throws TException {
    return measureRemoteCall(
        ServiceMethod.POLL_FOR_ACTIVITY_TASK, () -> pollForActivityTask(request));
  }

  private PollForActivityTaskResponse pollForActivityTask(PollForActivityTaskRequest pollRequest)
      throws TException {
    ThriftResponse<WorkflowService.PollForActivityTask_result> response = null;
    try {
      ThriftRequest<WorkflowService.PollForActivityTask_args> request =
          buildThriftRequest(
              "PollForActivityTask",
              new WorkflowService.PollForActivityTask_args(pollRequest),
              options.getRpcLongPollTimeoutMillis());
      response = doRemoteCall(request);
      WorkflowService.PollForActivityTask_result result =
          response.getBody(WorkflowService.PollForActivityTask_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("PollForActivityTask failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public RecordActivityTaskHeartbeatResponse RecordActivityTaskHeartbeat(
      RecordActivityTaskHeartbeatRequest request) throws TException {
    return measureRemoteCall(
        ServiceMethod.RECORD_ACTIVITY_TASK_HEARTBEAT, () -> recordActivityTaskHeartbeat(request));
  }

  private RecordActivityTaskHeartbeatResponse recordActivityTaskHeartbeat(
      RecordActivityTaskHeartbeatRequest heartbeatRequest) throws TException {
    ThriftResponse<WorkflowService.RecordActivityTaskHeartbeat_result> response = null;
    try {
      ThriftRequest<WorkflowService.RecordActivityTaskHeartbeat_args> request =
          buildThriftRequest(
              "RecordActivityTaskHeartbeat",
              new WorkflowService.RecordActivityTaskHeartbeat_args(heartbeatRequest));
      response = doRemoteCall(request);
      WorkflowService.RecordActivityTaskHeartbeat_result result =
          response.getBody(WorkflowService.RecordActivityTaskHeartbeat_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RecordActivityTaskHeartbeat failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public RecordActivityTaskHeartbeatResponse RecordActivityTaskHeartbeatByID(
      RecordActivityTaskHeartbeatByIDRequest heartbeatRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, DomainNotActiveError,
          LimitExceededError, ServiceBusyError, TException {
    return measureRemoteCall(
        ServiceMethod.RECORD_ACTIVITY_TASK_HEARTBEAT_BY_ID,
        () -> recordActivityTaskHeartbeatByID(heartbeatRequest));
  }

  private RecordActivityTaskHeartbeatResponse recordActivityTaskHeartbeatByID(
      RecordActivityTaskHeartbeatByIDRequest heartbeatRequest) throws TException {
    ThriftResponse<WorkflowService.RecordActivityTaskHeartbeatByID_result> response = null;
    try {
      ThriftRequest<WorkflowService.RecordActivityTaskHeartbeatByID_args> request =
          buildThriftRequest(
              "RecordActivityTaskHeartbeatByID",
              new WorkflowService.RecordActivityTaskHeartbeatByID_args(heartbeatRequest));
      response = doRemoteCall(request);
      WorkflowService.RecordActivityTaskHeartbeatByID_result result =
          response.getBody(WorkflowService.RecordActivityTaskHeartbeatByID_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RecordActivityTaskHeartbeatByID failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RespondActivityTaskCompleted(RespondActivityTaskCompletedRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.RESPOND_ACTIVITY_TASK_COMPLETED, () -> respondActivityTaskCompleted(request));
  }

  private void respondActivityTaskCompleted(RespondActivityTaskCompletedRequest completeRequest)
      throws TException {
    ThriftResponse<WorkflowService.RespondActivityTaskCompleted_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondActivityTaskCompleted_args> request =
          buildThriftRequest(
              "RespondActivityTaskCompleted",
              new WorkflowService.RespondActivityTaskCompleted_args(completeRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondActivityTaskCompleted_result result =
          response.getBody(WorkflowService.RespondActivityTaskCompleted_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RespondActivityTaskCompleted failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RespondActivityTaskCompletedByID(RespondActivityTaskCompletedByIDRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.RESPOND_ACTIVITY_TASK_COMPLETED_BY_ID,
        () -> respondActivityTaskCompletedByID(request));
  }

  private void respondActivityTaskCompletedByID(
      RespondActivityTaskCompletedByIDRequest completeRequest) throws TException {
    ThriftResponse<WorkflowService.RespondActivityTaskCompletedByID_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondActivityTaskCompletedByID_args> request =
          buildThriftRequest(
              "RespondActivityTaskCompletedByID",
              new WorkflowService.RespondActivityTaskCompletedByID_args(completeRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondActivityTaskCompletedByID_result result =
          response.getBody(WorkflowService.RespondActivityTaskCompletedByID_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RespondActivityTaskCompletedByID failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RespondActivityTaskFailed(RespondActivityTaskFailedRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.RESPOND_ACTIVITY_TASK_FAILED, () -> respondActivityTaskFailed(request));
  }

  private void respondActivityTaskFailed(RespondActivityTaskFailedRequest failRequest)
      throws TException {
    ThriftResponse<WorkflowService.RespondActivityTaskFailed_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondActivityTaskFailed_args> request =
          buildThriftRequest(
              "RespondActivityTaskFailed",
              new WorkflowService.RespondActivityTaskFailed_args(failRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondActivityTaskFailed_result result =
          response.getBody(WorkflowService.RespondActivityTaskFailed_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RespondActivityTaskFailed failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RespondActivityTaskFailedByID(RespondActivityTaskFailedByIDRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.RESPOND_ACTIVITY_TASK_FAILED_BY_ID,
        () -> respondActivityTaskFailedByID(request));
  }

  private void respondActivityTaskFailedByID(RespondActivityTaskFailedByIDRequest failRequest)
      throws TException {
    ThriftResponse<WorkflowService.RespondActivityTaskFailedByID_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondActivityTaskFailedByID_args> request =
          buildThriftRequest(
              "RespondActivityTaskFailedByID",
              new WorkflowService.RespondActivityTaskFailedByID_args(failRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondActivityTaskFailedByID_result result =
          response.getBody(WorkflowService.RespondActivityTaskFailedByID_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RespondActivityTaskFailedByID failedByID with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RespondActivityTaskCanceled(RespondActivityTaskCanceledRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.RESPOND_ACTIVITY_TASK_CANCELED, () -> respondActivityTaskCanceled(request));
  }

  private void respondActivityTaskCanceled(RespondActivityTaskCanceledRequest canceledRequest)
      throws TException {
    ThriftResponse<WorkflowService.RespondActivityTaskCanceled_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondActivityTaskCanceled_args> request =
          buildThriftRequest(
              "RespondActivityTaskCanceled",
              new WorkflowService.RespondActivityTaskCanceled_args(canceledRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondActivityTaskCanceled_result result =
          response.getBody(WorkflowService.RespondActivityTaskCanceled_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RespondActivityTaskCanceled failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RespondActivityTaskCanceledByID(RespondActivityTaskCanceledByIDRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.RESPOND_ACTIVITY_TASK_CANCELED_BY_ID,
        () -> respondActivityTaskCanceledByID(request));
  }

  private void respondActivityTaskCanceledByID(
      RespondActivityTaskCanceledByIDRequest canceledByIDRequest) throws TException {
    ThriftResponse<WorkflowService.RespondActivityTaskCanceledByID_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondActivityTaskCanceledByID_args> request =
          buildThriftRequest(
              "RespondActivityTaskCanceledByID",
              new WorkflowService.RespondActivityTaskCanceledByID_args(canceledByIDRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondActivityTaskCanceledByID_result result =
          response.getBody(WorkflowService.RespondActivityTaskCanceledByID_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RespondActivityTaskCanceledByID failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RequestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.REQUEST_CANCEL_WORKFLOW_EXECUTION,
        () -> requestCancelWorkflowExecution(request));
  }

  private void requestCancelWorkflowExecution(RequestCancelWorkflowExecutionRequest cancelRequest)
      throws TException {
    cancelRequest.setRequestId(UUID.randomUUID().toString());
    ThriftResponse<WorkflowService.RequestCancelWorkflowExecution_result> response = null;
    try {
      ThriftRequest<WorkflowService.RequestCancelWorkflowExecution_args> request =
          buildThriftRequest(
              "RequestCancelWorkflowExecution",
              new WorkflowService.RequestCancelWorkflowExecution_args(cancelRequest));
      response = doRemoteCall(request);
      WorkflowService.RequestCancelWorkflowExecution_result result =
          response.getBody(WorkflowService.RequestCancelWorkflowExecution_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetCancellationAlreadyRequestedError()) {
        throw result.getCancellationAlreadyRequestedError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RequestCancelWorkflowExecution failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void SignalWorkflowExecution(SignalWorkflowExecutionRequest request) throws TException {
    measureRemoteProc(
        ServiceMethod.SIGNAL_WORKFLOW_EXECUTION, () -> signalWorkflowExecution(request));
  }

  private void signalWorkflowExecution(SignalWorkflowExecutionRequest signalRequest)
      throws TException {
    ThriftResponse<WorkflowService.SignalWorkflowExecution_result> response = null;
    try {
      ThriftRequest<WorkflowService.SignalWorkflowExecution_args> request =
          buildThriftRequest(
              "SignalWorkflowExecution",
              new WorkflowService.SignalWorkflowExecution_args(signalRequest));
      response = doRemoteCall(request);
      WorkflowService.SignalWorkflowExecution_result result =
          response.getBody(WorkflowService.SignalWorkflowExecution_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("SignalWorkflowExecution failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public StartWorkflowExecutionResponse SignalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionRequest signalWithStartRequest) throws TException {
    return measureRemoteCall(
        ServiceMethod.SIGNAL_WITH_START_WORKFLOW_EXECUTION,
        () -> signalWithStartWorkflowExecution(signalWithStartRequest));
  }

  // TODO: https://github.com/uber/cadence-java-client/issues/359
  @Override
  public ResetWorkflowExecutionResponse ResetWorkflowExecution(
      ResetWorkflowExecutionRequest resetRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          DomainNotActiveError, LimitExceededError, ClientVersionNotSupportedError, TException {
    return null;
  }

  private StartWorkflowExecutionResponse signalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionRequest signalWithStartRequest) throws TException {
    signalWithStartRequest.setRequestId(UUID.randomUUID().toString());
    ThriftResponse<WorkflowService.SignalWithStartWorkflowExecution_result> response = null;
    try {
      ThriftRequest<WorkflowService.SignalWithStartWorkflowExecution_args> request =
          buildThriftRequest(
              "SignalWithStartWorkflowExecution",
              new WorkflowService.SignalWithStartWorkflowExecution_args(signalWithStartRequest));
      response = doRemoteCall(request);
      WorkflowService.SignalWithStartWorkflowExecution_result result =
          response.getBody(WorkflowService.SignalWithStartWorkflowExecution_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      throw new TException("SignalWithStartWorkflowExecution failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void TerminateWorkflowExecution(TerminateWorkflowExecutionRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.TERMINATE_WORKFLOW_EXECUTION, () -> terminateWorkflowExecution(request));
  }

  private void terminateWorkflowExecution(TerminateWorkflowExecutionRequest terminateRequest)
      throws TException {
    ThriftResponse<WorkflowService.TerminateWorkflowExecution_result> response = null;
    try {
      ThriftRequest<WorkflowService.TerminateWorkflowExecution_args> request =
          buildThriftRequest(
              "TerminateWorkflowExecution",
              new WorkflowService.TerminateWorkflowExecution_args(terminateRequest));
      response = doRemoteCall(request);
      WorkflowService.TerminateWorkflowExecution_result result =
          response.getBody(WorkflowService.TerminateWorkflowExecution_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("TerminateWorkflowExecution failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public ListOpenWorkflowExecutionsResponse ListOpenWorkflowExecutions(
      ListOpenWorkflowExecutionsRequest request) throws TException {
    return measureRemoteCall(
        ServiceMethod.LIST_OPEN_WORKFLOW_EXECUTIONS, () -> listOpenWorkflowExecutions(request));
  }

  private ListOpenWorkflowExecutionsResponse listOpenWorkflowExecutions(
      ListOpenWorkflowExecutionsRequest listRequest) throws TException {
    ThriftResponse<WorkflowService.ListOpenWorkflowExecutions_result> response = null;
    try {
      ThriftRequest<WorkflowService.ListOpenWorkflowExecutions_args> request =
          buildThriftRequest(
              "ListOpenWorkflowExecutions",
              new WorkflowService.ListOpenWorkflowExecutions_args(listRequest));
      response = doRemoteCall(request);
      WorkflowService.ListOpenWorkflowExecutions_result result =
          response.getBody(WorkflowService.ListOpenWorkflowExecutions_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("ListOpenWorkflowExecutions failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public ListClosedWorkflowExecutionsResponse ListClosedWorkflowExecutions(
      ListClosedWorkflowExecutionsRequest request) throws TException {
    return measureRemoteCall(
        ServiceMethod.LIST_CLOSED_WORKFLOW_EXECUTIONS, () -> listClosedWorkflowExecutions(request));
  }

  private ListClosedWorkflowExecutionsResponse listClosedWorkflowExecutions(
      ListClosedWorkflowExecutionsRequest listRequest) throws TException {
    ThriftResponse<WorkflowService.ListClosedWorkflowExecutions_result> response = null;
    try {
      ThriftRequest<WorkflowService.ListClosedWorkflowExecutions_args> request =
          buildThriftRequest(
              "ListClosedWorkflowExecutions",
              new WorkflowService.ListClosedWorkflowExecutions_args(listRequest));
      response = doRemoteCall(request);
      WorkflowService.ListClosedWorkflowExecutions_result result =
          response.getBody(WorkflowService.ListClosedWorkflowExecutions_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      throw new TException("ListClosedWorkflowExecutions failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public ListWorkflowExecutionsResponse ListWorkflowExecutions(
      ListWorkflowExecutionsRequest request)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, TException {
    return measureRemoteCall(
        ServiceMethod.LIST_WORKFLOW_EXECUTIONS, () -> listWorkflowExecutions(request));
  }

  private ListWorkflowExecutionsResponse listWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest) throws TException {
    ThriftResponse<WorkflowService.ListWorkflowExecutions_result> response = null;
    try {
      ThriftRequest<WorkflowService.ListWorkflowExecutions_args> request =
          buildThriftRequest(
              "ListWorkflowExecutions",
              new WorkflowService.ListWorkflowExecutions_args(listRequest));
      response = doRemoteCall(request);
      WorkflowService.ListWorkflowExecutions_result result =
          response.getBody(WorkflowService.ListWorkflowExecutions_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetClientVersionNotSupportedError()) {
        throw result.getClientVersionNotSupportedError();
      }
      throw new TException("ListWorkflowExecutions failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public ListWorkflowExecutionsResponse ScanWorkflowExecutions(
      ListWorkflowExecutionsRequest request)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, TException {
    return measureRemoteCall(
        ServiceMethod.SCAN_WORKFLOW_EXECUTIONS, () -> scanWorkflowExecutions(request));
  }

  private ListWorkflowExecutionsResponse scanWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest) throws TException {
    ThriftResponse<WorkflowService.ScanWorkflowExecutions_result> response = null;
    try {
      ThriftRequest<WorkflowService.ScanWorkflowExecutions_args> request =
          buildThriftRequest(
              "ScanWorkflowExecutions",
              new WorkflowService.ScanWorkflowExecutions_args(listRequest));
      response = doRemoteCall(request);
      WorkflowService.ScanWorkflowExecutions_result result =
          response.getBody(WorkflowService.ScanWorkflowExecutions_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetClientVersionNotSupportedError()) {
        throw result.getClientVersionNotSupportedError();
      }
      throw new TException("ScanWorkflowExecutions failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public CountWorkflowExecutionsResponse CountWorkflowExecutions(
      CountWorkflowExecutionsRequest countRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, ServiceBusyError,
          ClientVersionNotSupportedError, TException {
    return measureRemoteCall(
        ServiceMethod.COUNT_WORKFLOW_EXECUTIONS, () -> countWorkflowExecutions(countRequest));
  }

  private CountWorkflowExecutionsResponse countWorkflowExecutions(
      CountWorkflowExecutionsRequest countRequest) throws TException {
    ThriftResponse<WorkflowService.CountWorkflowExecutions_result> response = null;
    try {
      ThriftRequest<WorkflowService.CountWorkflowExecutions_args> request =
          buildThriftRequest(
              "CountWorkflowExecutions",
              new WorkflowService.CountWorkflowExecutions_args(countRequest));
      response = doRemoteCall(request);
      WorkflowService.CountWorkflowExecutions_result result =
          response.getBody(WorkflowService.CountWorkflowExecutions_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetClientVersionNotSupportedError()) {
        throw result.getClientVersionNotSupportedError();
      }
      throw new TException("CountWorkflowExecutions failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public GetSearchAttributesResponse GetSearchAttributes()
      throws InternalServiceError, ServiceBusyError, ClientVersionNotSupportedError, TException {
    return measureRemoteCall(ServiceMethod.GET_SEARCH_ATTRIBUTES, () -> getSearchAttributes());
  }

  private GetSearchAttributesResponse getSearchAttributes() throws TException {
    ThriftResponse<WorkflowService.GetSearchAttributes_result> response = null;
    try {
      ThriftRequest<WorkflowService.GetSearchAttributes_args> request =
          buildThriftRequest("GetSearchAttributes", new WorkflowService.GetSearchAttributes_args());
      response = doRemoteCall(request);
      WorkflowService.GetSearchAttributes_result result =
          response.getBody(WorkflowService.GetSearchAttributes_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetClientVersionNotSupportedError()) {
        throw result.getClientVersionNotSupportedError();
      }
      throw new TException("GetSearchAttributes failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void RespondQueryTaskCompleted(RespondQueryTaskCompletedRequest request)
      throws TException {
    measureRemoteProc(
        ServiceMethod.RESPOND_QUERY_TASK_COMPLETED, () -> respondQueryTaskCompleted(request));
  }

  private void respondQueryTaskCompleted(RespondQueryTaskCompletedRequest completeRequest)
      throws TException {
    ThriftResponse<WorkflowService.RespondQueryTaskCompleted_result> response = null;
    try {
      ThriftRequest<WorkflowService.RespondQueryTaskCompleted_args> request =
          buildThriftRequest(
              "RespondQueryTaskCompleted",
              new WorkflowService.RespondQueryTaskCompleted_args(completeRequest));
      response = doRemoteCall(request);
      WorkflowService.RespondQueryTaskCompleted_result result =
          response.getBody(WorkflowService.RespondQueryTaskCompleted_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return;
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("RespondQueryTaskCompleted failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public QueryWorkflowResponse QueryWorkflow(QueryWorkflowRequest request) throws TException {
    return measureRemoteCall(ServiceMethod.QUERY_WORKFLOW, () -> queryWorkflow(request));
  }

  private QueryWorkflowResponse queryWorkflow(QueryWorkflowRequest queryRequest) throws TException {
    ThriftResponse<WorkflowService.QueryWorkflow_result> response = null;
    try {
      ThriftRequest<WorkflowService.QueryWorkflow_args> request =
          buildThriftRequest(
              "QueryWorkflow",
              new WorkflowService.QueryWorkflow_args(queryRequest),
              options.getRpcQueryTimeoutMillis());
      response = doRemoteCall(request);
      WorkflowService.QueryWorkflow_result result =
          response.getBody(WorkflowService.QueryWorkflow_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetQueryFailedError()) {
        throw result.getQueryFailedError();
      }

      throw new TException("QueryWorkflow failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public ResetStickyTaskListResponse ResetStickyTaskList(ResetStickyTaskListRequest resetRequest)
      throws BadRequestError, InternalServiceError, EntityNotExistsError, LimitExceededError,
          ServiceBusyError, DomainNotActiveError, TException {
    return measureRemoteCall(
        ServiceMethod.RESET_STICKY_TASK_LIST, () -> resetStickyTaskList(resetRequest));
  }

  private ResetStickyTaskListResponse resetStickyTaskList(ResetStickyTaskListRequest queryRequest)
      throws TException {
    ThriftResponse<WorkflowService.ResetStickyTaskList_result> response = null;
    try {
      ThriftRequest<WorkflowService.ResetStickyTaskList_args> request =
          buildThriftRequest(
              "ResetStickyTaskList",
              new WorkflowService.ResetStickyTaskList_args(queryRequest),
              options.getRpcQueryTimeoutMillis());
      response = doRemoteCall(request);
      WorkflowService.ResetStickyTaskList_result result =
          response.getBody(WorkflowService.ResetStickyTaskList_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetDomainNotActiveError()) {
        throw result.getDomainNotActiveError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("ResetStickyTaskList failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public DescribeWorkflowExecutionResponse DescribeWorkflowExecution(
      DescribeWorkflowExecutionRequest request) throws TException {
    return measureRemoteCall(
        ServiceMethod.DESCRIBE_WORKFLOW_EXECUTION, () -> describeWorkflowExecution(request));
  }

  private DescribeWorkflowExecutionResponse describeWorkflowExecution(
      DescribeWorkflowExecutionRequest describeRequest) throws TException {
    ThriftResponse<WorkflowService.DescribeWorkflowExecution_result> response = null;
    try {
      ThriftRequest<WorkflowService.DescribeWorkflowExecution_args> request =
          buildThriftRequest(
              "DescribeWorkflowExecution",
              new WorkflowService.DescribeWorkflowExecution_args(describeRequest));
      response = doRemoteCall(request);
      WorkflowService.DescribeWorkflowExecution_result result =
          response.getBody(WorkflowService.DescribeWorkflowExecution_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("DescribeWorkflowExecution failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public DescribeTaskListResponse DescribeTaskList(DescribeTaskListRequest request)
      throws TException {
    return measureRemoteCall(ServiceMethod.DESCRIBE_TASK_LIST, () -> describeTaskList(request));
  }

  private DescribeTaskListResponse describeTaskList(DescribeTaskListRequest describeRequest)
      throws TException {
    ThriftResponse<WorkflowService.DescribeTaskList_result> response = null;
    try {
      ThriftRequest<WorkflowService.DescribeTaskList_args> request =
          buildThriftRequest(
              "DescribeTaskList", new WorkflowService.DescribeTaskList_args(describeRequest));
      response = doRemoteCall(request);
      WorkflowService.DescribeTaskList_result result =
          response.getBody(WorkflowService.DescribeTaskList_result.class);
      if (response.getResponseCode() == ResponseCode.OK) {
        return result.getSuccess();
      }
      if (result.isSetBadRequestError()) {
        throw result.getBadRequestError();
      }
      if (result.isSetInternalServiceError()) {
        throw result.getInternalServiceError();
      }
      if (result.isSetEntityNotExistError()) {
        throw result.getEntityNotExistError();
      }
      if (result.isSetServiceBusyError()) {
        throw result.getServiceBusyError();
      }
      if (result.isSetLimitExceededError()) {
        throw result.getLimitExceededError();
      }
      throw new TException("DescribeTaskList failed with unknown error:" + result);
    } finally {
      if (response != null) {
        response.release();
      }
    }
  }

  @Override
  public void StartWorkflowExecution(
      StartWorkflowExecutionRequest startRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @SuppressWarnings({"unchecked", "FutureReturnValueIgnored"})
  @Override
  public void GetWorkflowExecutionHistory(
      GetWorkflowExecutionHistoryRequest getRequest, AsyncMethodCallback resultHandler) {
    CompletableFuture<ThriftResponse<GetWorkflowExecutionHistory_result>> response = null;
    try {
      ThriftRequest<WorkflowService.GetWorkflowExecutionHistory_args> request =
          buildThriftRequest(
              "GetWorkflowExecutionHistory",
              new WorkflowService.GetWorkflowExecutionHistory_args(getRequest));
      response = doRemoteCallAsync(request);

      response
          .whenComplete(
              (r, e) -> {
                if (e != null) {
                  resultHandler.onError(CheckedExceptionWrapper.wrap(e));
                  return;
                }
                WorkflowService.GetWorkflowExecutionHistory_result result =
                    r.getBody(WorkflowService.GetWorkflowExecutionHistory_result.class);

                if (r.getResponseCode() == ResponseCode.OK) {
                  resultHandler.onComplete(result.getSuccess());
                  return;
                }
                if (result.isSetBadRequestError()) {
                  resultHandler.onError(result.getBadRequestError());
                  return;
                }
                if (result.isSetInternalServiceError()) {
                  resultHandler.onError(result.getInternalServiceError());
                  return;
                }
                if (result.isSetEntityNotExistError()) {
                  resultHandler.onError(result.getEntityNotExistError());
                  return;
                }
                if (result.isSetServiceBusyError()) {
                  resultHandler.onError(result.getServiceBusyError());
                  return;
                }
                resultHandler.onError(
                    new TException(
                        "GetWorkflowExecutionHistory failed with unknown " + "error:" + result));
                return;
              })
          .exceptionally(
              (e) -> {
                log.error("Unexpected error in GetWorkflowExecutionHistory", e);
                return null;
              });
    } finally {
      if (response != null && response.isDone()) {
        response.join().release();
      }
    }
  }

  @Override
  public void PollForDecisionTask(
      PollForDecisionTaskRequest pollRequest, AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondDecisionTaskCompleted(
      RespondDecisionTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondDecisionTaskFailed(
      RespondDecisionTaskFailedRequest failedRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void PollForActivityTask(
      PollForActivityTaskRequest pollRequest, AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RecordActivityTaskHeartbeat(
      RecordActivityTaskHeartbeatRequest heartbeatRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RecordActivityTaskHeartbeatByID(
      RecordActivityTaskHeartbeatByIDRequest heartbeatRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskCompleted(
      RespondActivityTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskCompletedByID(
      RespondActivityTaskCompletedByIDRequest completeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskFailed(
      RespondActivityTaskFailedRequest failRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskFailedByID(
      RespondActivityTaskFailedByIDRequest failRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskCanceled(
      RespondActivityTaskCanceledRequest canceledRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondActivityTaskCanceledByID(
      RespondActivityTaskCanceledByIDRequest canceledRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RequestCancelWorkflowExecution(
      RequestCancelWorkflowExecutionRequest cancelRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void SignalWorkflowExecution(
      SignalWorkflowExecutionRequest signalRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void SignalWithStartWorkflowExecution(
      SignalWithStartWorkflowExecutionRequest signalWithStartRequest,
      AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ResetWorkflowExecution(
      ResetWorkflowExecutionRequest resetRequest, AsyncMethodCallback resultHandler)
      throws TException {}

  @Override
  public void TerminateWorkflowExecution(
      TerminateWorkflowExecutionRequest terminateRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListOpenWorkflowExecutions(
      ListOpenWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListClosedWorkflowExecutions(
      ListClosedWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ScanWorkflowExecutions(
      ListWorkflowExecutionsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void CountWorkflowExecutions(
      CountWorkflowExecutionsRequest countRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void GetSearchAttributes(AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RespondQueryTaskCompleted(
      RespondQueryTaskCompletedRequest completeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ResetStickyTaskList(
      ResetStickyTaskListRequest resetRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void QueryWorkflow(QueryWorkflowRequest queryRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void DescribeWorkflowExecution(
      DescribeWorkflowExecutionRequest describeRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void DescribeTaskList(DescribeTaskListRequest request, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void RegisterDomain(
      RegisterDomainRequest registerRequest, AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void DescribeDomain(
      DescribeDomainRequest describeRequest, AsyncMethodCallback resultHandler) throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void ListDomains(ListDomainsRequest listRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void UpdateDomain(UpdateDomainRequest updateRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }

  @Override
  public void DeprecateDomain(
      DeprecateDomainRequest deprecateRequest, AsyncMethodCallback resultHandler)
      throws TException {
    throw new UnsupportedOperationException("not implemented");
  }
}
