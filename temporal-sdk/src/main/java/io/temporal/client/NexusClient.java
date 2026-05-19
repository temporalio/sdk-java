package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.lang.reflect.Type;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Client for managing standalone Nexus operation executions. Obtain an instance via {@link
 * #newInstance(WorkflowServiceStubs)} or {@link #newInstance(WorkflowServiceStubs,
 * NexusClientOptions)}. Do not create this object per request; share it for the lifetime of the
 * process.
 *
 * <p>Standalone Nexus operations run independently of any workflow — they are scheduled, monitored,
 * and managed directly through this client (and the service-bound clients it produces) rather than
 * from within a workflow execution.
 *
 * <p>To start operations, build a service-bound client and call {@code start}/{@code execute}:
 *
 * <pre>{@code
 * NexusClient client = NexusClient.newInstance(stubs, options);
 *
 * // Typed: bind to an @ServiceInterface and invoke a method reference.
 * NexusServiceClient<MyService> svc =
 *     NexusServiceClient.newInstance(MyService.class, "my-endpoint", stubs, options);
 * String result = svc.execute(MyService::greet, "world");
 *
 * // Untyped: dispatch by operation name string.
 * UntypedNexusServiceClient untyped =
 *     client.newUntypedNexusServiceClient("my-endpoint", "MyService");
 * UntypedNexusOperationHandle handle = untyped.start("greet", null, "world");
 * }</pre>
 *
 * <p>To act on an existing operation (describe, cancel, terminate, get result), obtain a handle via
 * {@link #getHandle}:
 *
 * <pre>{@code
 * NexusOperationHandle<String> handle = client.getHandle(operationId, runId, String.class);
 * String result = handle.getResult();
 * handle.cancel("user requested");
 * }</pre>
 *
 * <p>For visibility queries across all operations in the namespace, see {@link
 * #listNexusOperationExecutions} and {@link #countNexusOperationExecutions}.
 *
 * @see NexusServiceClient
 * @see UntypedNexusServiceClient
 * @see NexusOperationHandle
 */
@Experimental
public interface NexusClient {

  /**
   * Creates a client with default {@link NexusClientOptions}.
   *
   * @param service gRPC stubs connected to a Temporal Service endpoint
   */
  static NexusClient newInstance(WorkflowServiceStubs service) {
    return NexusClientImpl.newInstance(service, NexusClientOptions.getDefaultInstance());
  }

  /**
   * Creates a client with the supplied options.
   *
   * @param service gRPC stubs connected to a Temporal Service endpoint
   * @param options namespace, data converter, interceptors, and defaults applied to operations
   *     started through this client
   */
  static NexusClient newInstance(WorkflowServiceStubs service, NexusClientOptions options) {
    return NexusClientImpl.newInstance(service, options);
  }

  /** Returns the underlying gRPC stubs this client routes RPCs through. */
  WorkflowServiceStubs getWorkflowServiceStubs();

  /**
   * Returns an untyped handle to an existing operation execution, targeting the latest run. To bind
   * a result type, wrap the handle with {@link NexusOperationHandle#fromUntyped}.
   *
   * @param operationId the user-assigned operation ID
   * @return an untyped handle
   */
  UntypedNexusOperationHandle getHandle(String operationId);

  /**
   * Returns an untyped handle to an existing operation execution, optionally pinned to a specific
   * run.
   *
   * @param operationId the user-assigned operation ID
   * @param runId the server-assigned run ID, or {@code null} to target the latest run
   * @return an untyped handle
   */
  UntypedNexusOperationHandle getHandle(String operationId, @Nullable String runId);

  /**
   * Returns a typed handle to an existing operation execution, bound to {@code resultClass}.
   *
   * @param operationId the user-assigned operation ID
   * @param runId the server-assigned run ID, or {@code null} to target the latest run
   * @param resultClass expected result type
   * @param <R> result type
   */
  <R> NexusOperationHandle<R> getHandle(
      String operationId, @Nullable String runId, Class<R> resultClass);

  /**
   * Returns a typed handle to an existing operation execution, bound to {@code resultClass}/{@code
   * resultType}. Use the {@code resultType} variant when the result is a generic type whose
   * parameters cannot be captured by {@link Class} alone (e.g. {@code List<String>}).
   *
   * @param operationId the user-assigned operation ID
   * @param runId the server-assigned run ID, or {@code null} to target the latest run
   * @param resultClass expected result class
   * @param resultType generic type for deserialization; may be {@code null}
   * @param <R> result type
   */
  <R> NexusOperationHandle<R> getHandle(
      String operationId, @Nullable String runId, Class<R> resultClass, @Nullable Type resultType);

  /**
   * Builds an untyped service-bound client targeting the given endpoint and service. Use this to
   * dispatch operations by name string when no service interface is available.
   *
   * @param endpoint Nexus endpoint name registered on the Temporal Service
   * @param serviceName Nexus service name on that endpoint
   */
  UntypedNexusServiceClient newUntypedNexusServiceClient(String endpoint, String serviceName);

  /**
   * Returns a stream of standalone Nexus operation executions matching the given visibility query.
   * The stream paginates lazily over server-side results — pages are fetched on demand as the
   * stream is consumed.
   *
   * @param query Temporal visibility query string, or {@code null} to return all executions in the
   *     client namespace
   * @return a lazy stream of matching executions
   */
  Stream<NexusOperationExecutionMetadata> listNexusOperationExecutions(@Nullable String query);

  /**
   * Returns the count of standalone Nexus operation executions matching the given visibility query,
   * optionally with aggregation groups.
   *
   * @param query Temporal visibility query string, or {@code null} to count all executions in the
   *     client namespace
   * @return execution count, optionally with aggregation groups when the query uses {@code GROUP
   *     BY}
   */
  NexusOperationExecutionCount countNexusOperationExecutions(@Nullable String query);
}
