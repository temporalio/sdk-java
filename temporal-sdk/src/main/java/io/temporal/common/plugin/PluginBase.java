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

package io.temporal.common.plugin;

import io.temporal.common.Experimental;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Convenience base class for plugins that implement both {@link ClientPlugin} and {@link
 * WorkerPlugin}. All methods have default no-op implementations.
 *
 * <p>This is the recommended way to create plugins that need to customize both client and worker
 * behavior. Plugins that extend this class will automatically be propagated from the client to
 * workers.
 *
 * <p>Example:
 *
 * <pre>{@code
 * public class TracingPlugin extends PluginBase {
 *     private final Tracer tracer;
 *
 *     public TracingPlugin(Tracer tracer) {
 *         super("io.temporal.tracing");
 *         this.tracer = tracer;
 *     }
 *
 *     @Override
 *     public WorkflowClientOptions.Builder configureClient(
 *             WorkflowClientOptions.Builder builder) {
 *         // Add tracing interceptor to client
 *         return builder.setInterceptors(new TracingClientInterceptor(tracer));
 *     }
 *
 *     @Override
 *     public WorkerFactoryOptions.Builder configureWorkerFactory(
 *             WorkerFactoryOptions.Builder builder) {
 *         // Add tracing interceptor to workers
 *         return builder.setWorkerInterceptors(new TracingWorkerInterceptor(tracer));
 *     }
 * }
 * }</pre>
 *
 * @see ClientPlugin
 * @see WorkerPlugin
 */
@Experimental
public abstract class PluginBase implements ClientPlugin, WorkerPlugin {

  private final String name;

  /**
   * Creates a new plugin with the specified name.
   *
   * @param name a unique name for this plugin, used for logging and duplicate detection.
   *     Recommended format: "organization.plugin-name" (e.g., "io.temporal.tracing")
   * @throws NullPointerException if name is null
   */
  protected PluginBase(@Nonnull String name) {
    this.name = Objects.requireNonNull(name, "Plugin name cannot be null");
  }

  @Override
  @Nonnull
  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{name='" + name + "'}";
  }
}
