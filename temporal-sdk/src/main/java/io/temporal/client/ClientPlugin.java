/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.client;

import io.temporal.common.Experimental;
import io.temporal.common.SimplePlugin;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubs.ClientPluginCallback;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import javax.annotation.Nonnull;

/**
 * Plugin interface for customizing Temporal client configuration and lifecycle.
 *
 * <p>Plugins participate in two phases:
 *
 * <ul>
 *   <li><b>Configuration phase:</b> Plugins are called in registration order to modify options
 *   <li><b>Connection phase:</b> Plugins are called in reverse order to wrap service client
 *       creation
 * </ul>
 *
 * <p>Example implementation:
 *
 * <pre>{@code
 * public class LoggingPlugin extends SimplePlugin {
 *     public LoggingPlugin() {
 *         super("my-org.logging");
 *     }
 *
 *     @Override
 *     public WorkflowClientOptions.Builder configureClient(
 *             WorkflowClientOptions.Builder builder) {
 *         // Add custom interceptor
 *         return builder.setInterceptors(new LoggingInterceptor());
 *     }
 *
 *     @Override
 *     public WorkflowServiceStubs connectServiceClient(
 *             WorkflowServiceStubsOptions options,
 *             ServiceStubsSupplier next) throws Exception {
 *         logger.info("Connecting to Temporal at {}", options.getTarget());
 *         WorkflowServiceStubs stubs = next.get();
 *         logger.info("Connected successfully");
 *         return stubs;
 *     }
 * }
 * }</pre>
 *
 * @see io.temporal.worker.WorkerPlugin
 * @see SimplePlugin
 */
@Experimental
public interface ClientPlugin extends ClientPluginCallback {

  /**
   * Returns a unique name for this plugin. Used for logging and duplicate detection. Recommended
   * format: "organization.plugin-name" (e.g., "io.temporal.tracing")
   *
   * @return fully qualified plugin name
   */
  @Nonnull
  String getName();

  /**
   * Allows the plugin to modify service stubs options before the service stubs are created. Called
   * during configuration phase in forward (registration) order.
   *
   * @param builder the options builder to modify
   * @return the modified builder (may return same instance or new builder)
   */
  @Override
  @Nonnull
  default WorkflowServiceStubsOptions.Builder configureServiceStubs(
      @Nonnull WorkflowServiceStubsOptions.Builder builder) {
    return builder;
  }

  /**
   * Allows the plugin to modify workflow client options before the client is created. Called during
   * configuration phase in forward (registration) order.
   *
   * @param builder the options builder to modify
   * @return the modified builder
   */
  @Nonnull
  default WorkflowClientOptions.Builder configureClient(
      @Nonnull WorkflowClientOptions.Builder builder) {
    return builder;
  }

  /**
   * Allows the plugin to wrap service client connection. Called during connection phase in reverse
   * order (first plugin wraps all others).
   *
   * <p>Example:
   *
   * <pre>{@code
   * @Override
   * public WorkflowServiceStubs connectServiceClient(
   *         WorkflowServiceStubsOptions options,
   *         ClientPluginCallback.ServiceStubsSupplier next) throws Exception {
   *     logger.info("Connecting to Temporal...");
   *     WorkflowServiceStubs stubs = next.get();
   *     logger.info("Connected successfully");
   *     return stubs;
   * }
   * }</pre>
   *
   * @param options the final options being used for connection
   * @param next supplier that creates the service stubs (calls next plugin or actual connection)
   * @return the service stubs (possibly wrapped or decorated)
   * @throws Exception if connection fails
   */
  @Override
  @Nonnull
  default WorkflowServiceStubs connectServiceClient(
      @Nonnull WorkflowServiceStubsOptions options,
      @Nonnull ClientPluginCallback.ServiceStubsSupplier next)
      throws Exception {
    return next.get();
  }
}
