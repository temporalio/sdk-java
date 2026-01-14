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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import javax.annotation.Nonnull;

/**
 * Discovers plugins using Java's {@link ServiceLoader} mechanism.
 *
 * <p>To register a plugin for automatic discovery, create files at:
 *
 * <ul>
 *   <li>{@code META-INF/services/io.temporal.common.plugin.ClientPlugin}
 *   <li>{@code META-INF/services/io.temporal.common.plugin.WorkerPlugin}
 * </ul>
 *
 * <p>containing the fully qualified class names of plugin implementations, one per line.
 *
 * <p>Example file content:
 *
 * <pre>
 * com.mycompany.temporal.TracingPlugin
 * com.mycompany.temporal.MetricsPlugin
 * </pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * // Discover all client plugins
 * List<ClientPlugin> clientPlugins = PluginDiscovery.discoverClientPlugins();
 *
 * // Discover all worker plugins
 * List<WorkerPlugin> workerPlugins = PluginDiscovery.discoverWorkerPlugins();
 *
 * // Discover all plugins (both types, deduplicated)
 * List<Object> allPlugins = PluginDiscovery.discoverAllPlugins();
 *
 * // Use with client options
 * WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
 *     .setPlugins(allPlugins)
 *     .build();
 * }</pre>
 *
 * @see ServiceLoader
 * @see ClientPlugin
 * @see WorkerPlugin
 */
@Experimental
public final class PluginDiscovery {

  private PluginDiscovery() {}

  /**
   * Discovers all available {@link ClientPlugin}s using the thread's context class loader.
   *
   * @return an unmodifiable list of discovered client plugins
   */
  @Nonnull
  public static List<ClientPlugin> discoverClientPlugins() {
    return discoverClientPlugins(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Discovers all available {@link ClientPlugin}s using the specified class loader.
   *
   * @param classLoader the class loader to use for discovery
   * @return an unmodifiable list of discovered client plugins
   */
  @Nonnull
  public static List<ClientPlugin> discoverClientPlugins(@Nonnull ClassLoader classLoader) {
    ServiceLoader<ClientPlugin> loader = ServiceLoader.load(ClientPlugin.class, classLoader);
    List<ClientPlugin> plugins = new ArrayList<>();
    for (ClientPlugin plugin : loader) {
      plugins.add(plugin);
    }
    return Collections.unmodifiableList(plugins);
  }

  /**
   * Discovers all available {@link WorkerPlugin}s using the thread's context class loader.
   *
   * @return an unmodifiable list of discovered worker plugins
   */
  @Nonnull
  public static List<WorkerPlugin> discoverWorkerPlugins() {
    return discoverWorkerPlugins(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Discovers all available {@link WorkerPlugin}s using the specified class loader.
   *
   * @param classLoader the class loader to use for discovery
   * @return an unmodifiable list of discovered worker plugins
   */
  @Nonnull
  public static List<WorkerPlugin> discoverWorkerPlugins(@Nonnull ClassLoader classLoader) {
    ServiceLoader<WorkerPlugin> loader = ServiceLoader.load(WorkerPlugin.class, classLoader);
    List<WorkerPlugin> plugins = new ArrayList<>();
    for (WorkerPlugin plugin : loader) {
      plugins.add(plugin);
    }
    return Collections.unmodifiableList(plugins);
  }

  /**
   * Discovers all available plugins (both {@link ClientPlugin} and {@link WorkerPlugin}) using the
   * thread's context class loader. Plugins that implement both interfaces are only included once.
   *
   * @return an unmodifiable list of discovered plugins
   */
  @Nonnull
  public static List<Object> discoverAllPlugins() {
    return discoverAllPlugins(Thread.currentThread().getContextClassLoader());
  }

  /**
   * Discovers all available plugins (both {@link ClientPlugin} and {@link WorkerPlugin}) using the
   * specified class loader. Plugins that implement both interfaces are only included once.
   *
   * @param classLoader the class loader to use for discovery
   * @return an unmodifiable list of discovered plugins
   */
  @Nonnull
  public static List<Object> discoverAllPlugins(@Nonnull ClassLoader classLoader) {
    List<Object> plugins = new ArrayList<>();

    // Add all ClientPlugins
    for (ClientPlugin plugin : discoverClientPlugins(classLoader)) {
      plugins.add(plugin);
    }

    // Add WorkerPlugins that aren't already in the list (avoid duplicates for dual-interface
    // plugins)
    for (WorkerPlugin plugin : discoverWorkerPlugins(classLoader)) {
      if (!plugins.contains(plugin)) {
        plugins.add(plugin);
      }
    }

    return Collections.unmodifiableList(plugins);
  }
}
