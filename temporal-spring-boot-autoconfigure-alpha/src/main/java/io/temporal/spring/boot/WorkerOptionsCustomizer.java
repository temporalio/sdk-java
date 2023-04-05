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

import io.temporal.worker.WorkerOptions;
import javax.annotation.Nonnull;

/**
 * Bean of this class can be added to Spring context to get a fine control over WorkerOptions
 * objects that are created by Temporal Spring Boot Autoconfigure module.
 *
 * <p>Only one bean of this or {@code TemporalOptionsCustomizer<WorkerOptions.Builder>} type can be
 * added to Spring context.
 */
public interface WorkerOptionsCustomizer extends TemporalOptionsCustomizer<WorkerOptions.Builder> {
  @Nonnull
  @Override
  default WorkerOptions.Builder customize(@Nonnull WorkerOptions.Builder optionsBuilder) {
    return optionsBuilder;
  }

  /**
   * This method can modify some fields of the provided {@code WorkerOptions.Builder} or create a
   * new builder altogether and return it back to be used. This method is called after the {@code
   * WorkerOptions.Builder} is initialized by the Temporal Spring Boot module, so changes done by
   * this method will override Spring Boot configuration values.
   *
   * @param optionsBuilder {@code *Options.Builder} to customize
   * @param workerName name of a Worker that will be created using the {@link WorkerOptions}
   *     produced by this call
   * @param taskQueue Task Queue of a Worker that will be created using the {@link WorkerOptions}
   *     produced by this call
   * @return modified {@code optionsBuilder} or a new builder instance to be used by the caller code
   */
  @Nonnull
  WorkerOptions.Builder customize(
      @Nonnull WorkerOptions.Builder optionsBuilder,
      @Nonnull String workerName,
      @Nonnull String taskQueue);
}
