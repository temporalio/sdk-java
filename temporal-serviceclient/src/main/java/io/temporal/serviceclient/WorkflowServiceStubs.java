package io.temporal.serviceclient;

import static io.temporal.internal.WorkflowThreadMarker.enforceNonWorkflowThread;

import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.testservice.InProcessGRPCServer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Initializes and holds gRPC blocking and future stubs. */
public interface WorkflowServiceStubs
    extends ServiceStubs<
        WorkflowServiceGrpc.WorkflowServiceBlockingStub,
        WorkflowServiceGrpc.WorkflowServiceFutureStub> {
  String HEALTH_CHECK_SERVICE_NAME = "temporal.api.workflowservice.v1.WorkflowService";

  /**
   * Creates WorkflowService gRPC stubs pointed on to the locally running Temporal Server. The
   * Server should be available on 127.0.0.1:7233
   */
  static WorkflowServiceStubs newLocalServiceStubs() {
    return newServiceStubs(WorkflowServiceStubsOptions.getDefaultInstance());
  }

  /**
   * Creates WorkflowService gRPC stubs.
   *
   * <p>This method creates stubs with "lazy" connectivity. The connection is not performed during
   * the creation time and happens on the first request. <br>
   * If you wish to perform a connection in an eager manner, call {@link
   * WorkflowServiceStubs#connect(Duration)} after creation or use {@link
   * #newConnectedServiceStubs(WorkflowServiceStubsOptions, Duration)} instead of this method.
   *
   * <p>Migration Note: This method doesn't respect {@link
   * WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)}, {@link
   * WorkflowServiceStubsOptions.Builder#setHealthCheckAttemptTimeout(Duration)} (boolean)} and
   * {@link WorkflowServiceStubsOptions.Builder#setHealthCheckTimeout(Duration)} (boolean)}. This
   * method is equivalent to {@link
   * WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)} set.
   *
   * @param options stub options to use
   */
  static WorkflowServiceStubs newServiceStubs(WorkflowServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new WorkflowServiceStubsImpl(null, options), WorkflowServiceStubs.class);
  }

  /**
   * Creates WorkflowService gRPC stubs and ensures connectivity with the server at the moment of
   * creation.
   *
   * <p>See {@link #newServiceStubs(WorkflowServiceStubsOptions)} if you prefer a lazy version of
   * this method that doesn't perform an eager connection. This method is functionally equivalent to
   * a sequence of {@link #newServiceStubs(WorkflowServiceStubsOptions)} and {@link
   * WorkflowServiceStubs#connect(Duration)}
   *
   * <p>Migration Note: This method doesn't respect {@link
   * WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)}, {@link
   * WorkflowServiceStubsOptions.Builder#setHealthCheckAttemptTimeout(Duration)} (boolean)} and
   * {@link WorkflowServiceStubsOptions.Builder#setHealthCheckTimeout(Duration)} (boolean)}. This
   * method is equivalent to {@link
   * WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)} not set.
   *
   * @param options stub options to use
   * @param timeout timeout to use in {@link WorkflowServiceStubs#connect(Duration)} call. If null,
   *     {@code options.getRpcTimeout()} will be used.
   */
  static WorkflowServiceStubs newConnectedServiceStubs(
      WorkflowServiceStubsOptions options, @Nullable Duration timeout) {
    WorkflowServiceStubs workflowServiceStubs = newServiceStubs(options);
    workflowServiceStubs.connect(timeout);
    return workflowServiceStubs;
  }

  /**
   * Create gRPC connection stubs using default options. The options default to the connection to
   * the locally running temporal service.
   *
   * @deprecated use {@link #newLocalServiceStubs()}.
   */
  @Deprecated
  static WorkflowServiceStubs newInstance() {
    return newInstance(WorkflowServiceStubsOptions.getDefaultInstance());
  }

  /**
   * Create gRPC connection stubs using provided options.
   *
   * @deprecated use {@link #newServiceStubs(WorkflowServiceStubsOptions)} or {@link
   *     #newConnectedServiceStubs(WorkflowServiceStubsOptions, Duration)}. Use {@link
   *     #newServiceStubs(WorkflowServiceStubsOptions)} to get the same behavior as with set {@link
   *     WorkflowServiceStubsOptions.Builder#setDisableHealthCheck(boolean)} (preferred). Use {@link
   *     #newConnectedServiceStubs(WorkflowServiceStubsOptions, Duration)} with {{@link
   *     WorkflowServiceStubsOptions.Builder#setHealthCheckTimeout(Duration)} as {@code timeout}
   *     (null if you didn't specify it).
   */
  @Deprecated
  static WorkflowServiceStubs newInstance(WorkflowServiceStubsOptions options) {
    if (options.getDisableHealthCheck()) {
      return newServiceStubs(options);
    } else {
      return newConnectedServiceStubs(options, options.getHealthCheckTimeout());
    }
  }

  /**
   * Create gRPC connection stubs that connect to the provided service implementation using an
   * in-memory channel. Useful for testing, usually with mock and spy services.
   *
   * @deprecated use {@link InProcessGRPCServer} to manage in-memory server and corresponded channel
   *     outside the stubs. Channel provided by {@link InProcessGRPCServer} should be supplied into
   *     {@link #newInstance(WorkflowServiceStubsOptions)} by specifying {{@link
   *     WorkflowServiceStubsOptions#getChannel()}}
   */
  @Deprecated
  static WorkflowServiceStubs newInstance(
      WorkflowServiceGrpc.WorkflowServiceImplBase service, WorkflowServiceStubsOptions options) {
    enforceNonWorkflowThread();
    return WorkflowThreadMarker.protectFromWorkflowThread(
        new WorkflowServiceStubsImpl(service, options), WorkflowServiceStubs.class);
  }

  /**
   * Creates WorkflowService gRPC stubs with plugin support.
   *
   * <p>This method applies plugins in two phases:
   *
   * <ol>
   *   <li><b>Configuration phase:</b> Each plugin's {@code configureServiceStubs} method is called
   *       in forward (registration) order to modify the options builder
   *   <li><b>Connection phase:</b> Each plugin's {@code connectServiceClient} method is called in
   *       reverse order to wrap the connection (first plugin wraps all others)
   * </ol>
   *
   * <p>This method creates stubs with "lazy" connectivity. The connection is not performed during
   * the creation time and happens on the first request.
   *
   * @param options stub options to use
   * @param plugins list of plugins to apply (plugins implementing io.temporal.client.Plugin are
   *     processed)
   * @return the workflow service stubs
   */
  static WorkflowServiceStubs newServiceStubs(
      @Nonnull WorkflowServiceStubsOptions options, @Nonnull List<?> plugins) {
    enforceNonWorkflowThread();

    // Apply plugin configuration phase (forward order)
    WorkflowServiceStubsOptions.Builder builder = WorkflowServiceStubsOptions.newBuilder(options);
    for (Object plugin : plugins) {
      if (plugin instanceof ClientPluginCallback) {
        builder = ((ClientPluginCallback) plugin).configureServiceStubs(builder);
      }
    }
    WorkflowServiceStubsOptions finalOptions = builder.validateAndBuildWithDefaults();

    // Build connection chain (reverse order for proper nesting)
    ClientPluginCallback.ServiceStubsSupplier connectionChain =
        () ->
            WorkflowThreadMarker.protectFromWorkflowThread(
                new WorkflowServiceStubsImpl(null, finalOptions), WorkflowServiceStubs.class);

    List<Object> reversed = new ArrayList<>(plugins);
    Collections.reverse(reversed);
    for (Object plugin : reversed) {
      if (plugin instanceof ClientPluginCallback) {
        final ClientPluginCallback.ServiceStubsSupplier next = connectionChain;
        final ClientPluginCallback callback = (ClientPluginCallback) plugin;
        connectionChain = () -> callback.connectServiceClient(finalOptions, next);
      }
    }

    try {
      return connectionChain.get();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create service stubs with plugins", e);
    }
  }

  /**
   * Callback interface for client plugins to participate in service stubs creation. This interface
   * is implemented by {@code io.temporal.client.Plugin} in the temporal-sdk module.
   */
  interface ClientPluginCallback {
    /**
     * Allows the plugin to modify service stubs options before the service stubs are created.
     *
     * @param builder the options builder to modify
     * @return the modified builder
     */
    @Nonnull
    WorkflowServiceStubsOptions.Builder configureServiceStubs(
        @Nonnull WorkflowServiceStubsOptions.Builder builder);

    /**
     * Allows the plugin to wrap service client connection.
     *
     * @param options the final options being used for connection
     * @param next supplier that creates the service stubs
     * @return the service stubs
     * @throws Exception if connection fails
     */
    @Nonnull
    WorkflowServiceStubs connectServiceClient(
        @Nonnull WorkflowServiceStubsOptions options, @Nonnull ServiceStubsSupplier next)
        throws Exception;

    /** Functional interface for the connection chain. */
    @FunctionalInterface
    interface ServiceStubsSupplier {
      WorkflowServiceStubs get() throws Exception;
    }
  }

  WorkflowServiceStubsOptions getOptions();
}
