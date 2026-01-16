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
import javax.annotation.Nonnull;

/**
 * Plugin interface for customizing Temporal workflow client configuration.
 *
 * <p>This interface is separate from {@link
 * io.temporal.serviceclient.WorkflowServiceStubs.ServiceStubsPlugin} to allow plugins that only
 * need to configure the workflow client without affecting the underlying gRPC connection.
 *
 * <p>Plugins that implement both {@code ServiceStubsPlugin} and {@code WorkflowClientPlugin} will
 * have their service stubs configuration applied when creating the service stubs, and their client
 * configuration applied when creating the workflow client.
 *
 * <p>Plugins that also implement {@link io.temporal.worker.WorkerPlugin} are automatically
 * propagated from the client to workers created from that client.
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
 *     public void configureClient(WorkflowClientOptions.Builder builder) {
 *         // Add custom interceptor
 *         builder.setInterceptors(new LoggingInterceptor());
 *     }
 * }
 * }</pre>
 *
 * @see io.temporal.serviceclient.WorkflowServiceStubs.ServiceStubsPlugin
 * @see io.temporal.worker.WorkerPlugin
 * @see SimplePlugin
 */
@Experimental
public interface WorkflowClientPlugin {

  /**
   * Returns a unique name for this plugin. Used for logging and duplicate detection. Recommended
   * format: "organization.plugin-name" (e.g., "io.temporal.tracing")
   *
   * @return fully qualified plugin name
   */
  @Nonnull
  String getName();

  /**
   * Allows the plugin to modify workflow client options before the client is created. Called during
   * configuration phase in forward (registration) order.
   *
   * @param builder the options builder to modify
   */
  void configureClient(@Nonnull WorkflowClientOptions.Builder builder);
}
