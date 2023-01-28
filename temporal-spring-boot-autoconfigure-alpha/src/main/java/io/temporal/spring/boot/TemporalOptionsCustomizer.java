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

package io.temporal.spring.boot;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import javax.annotation.Nonnull;

/**
 * Beans of this class can be added to Spring context to get a fine control over *Options objects
 * that are created by Temporal Spring Boot Autoconfigure module.
 *
 * <p>Only one bean of each generic type can be added to Spring context.
 *
 * @param <T> Temporal Options Builder to customize. Respected types: {@link
 *     WorkflowServiceStubsOptions.Builder}, {@link WorkflowClientOptions.Builder}, {@link
 *     TestEnvironmentOptions.Builder}, {@link WorkerOptions.Builder}, {@link
 *     WorkerFactoryOptions.Builder}
 * @see WorkerOptionsCustomizer to get access to a name and task queue of a worker that's being
 *     customized
 */
public interface TemporalOptionsCustomizer<T> {
  /**
   * This method can modify some fields of the provided builder or create a new builder altogether
   * and return it back to be used. This method is called after the {@code *Options.Builder} is
   * initialized by the Temporal Spring Boot module, so changes done by this method will override
   * Spring Boot configuration values.
   *
   * @param optionsBuilder {@code *Options.Builder} to customize
   * @return modified {@code optionsBuilder} or a new builder to be used by the caller code.
   */
  @Nonnull
  T customize(@Nonnull T optionsBuilder);
}
