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

package io.temporal.serviceclient;

import java.util.function.Supplier;
import javax.annotation.Nonnull;

/**
 * Plugin interface for customizing Temporal workflow service stubs configuration and connection.
 *
 * <p>This is a low-level plugin interface for configuring the gRPC connection to the Temporal
 * server. For most use cases, use {@code WorkflowClientPlugin} or {@code WorkerPlugin} in the
 * temporal-sdk module instead.
 *
 * <p>Plugins that implement both {@code WorkflowServiceStubsPlugin} and {@code
 * WorkflowClientPlugin} are automatically propagated from the service stubs to the workflow client.
 */
public interface WorkflowServiceStubsPlugin {
  /**
   * Returns a unique name for this plugin. Used for logging and duplicate detection. Recommended
   * format: "organization.plugin-name" (e.g., "io.temporal.tracing")
   *
   * @return fully qualified plugin name
   */
  @Nonnull
  String getName();

  /**
   * Allows the plugin to modify service stubs options before the service stubs are created.
   *
   * @param builder the options builder to modify
   */
  void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder);

  /**
   * Allows the plugin to wrap service client connection.
   *
   * @param options the final options being used for connection
   * @param next supplier that creates the service stubs (calls next plugin or actual connection)
   * @return the service stubs (possibly wrapped or decorated)
   */
  @Nonnull
  WorkflowServiceStubs connectServiceClient(
      @Nonnull WorkflowServiceStubsOptions options, @Nonnull Supplier<WorkflowServiceStubs> next);
}
