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

package io.temporal.client.schedules;

import io.temporal.common.Experimental;
import javax.annotation.Nonnull;

/**
 * Plugin interface for customizing Temporal schedule client configuration.
 *
 * <p>Plugins that implement both {@link io.temporal.serviceclient.WorkflowServiceStubsPlugin} and
 * {@code ScheduleClientPlugin} are automatically propagated from the service stubs to the schedule
 * client.
 *
 * @see io.temporal.serviceclient.WorkflowServiceStubsPlugin
 */
@Experimental
public interface ScheduleClientPlugin {

  /**
   * Returns a unique name for this plugin. Used for logging and duplicate detection. Recommended
   * format: "organization.plugin-name" (e.g., "io.temporal.tracing")
   *
   * @return fully qualified plugin name
   */
  @Nonnull
  String getName();

  /**
   * Allows the plugin to modify schedule client options before the client is created. Called during
   * configuration phase in forward (registration) order.
   *
   * @param builder the options builder to modify
   */
  void configureScheduleClient(@Nonnull ScheduleClientOptions.Builder builder);
}
