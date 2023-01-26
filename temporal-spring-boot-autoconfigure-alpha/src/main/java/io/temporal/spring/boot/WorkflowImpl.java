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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables the Workflow implementation class to be discovered by the Workers auto-discovery. This
 * annotation is not needed if only an explicit config is used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface WorkflowImpl {
  /**
   * @return names of Workers to register this workflow implementation with. Workers with these
   *     names must be present in the application config or auto-discovered from {@link
   *     #taskQueues()}. Worker is named by its task queue if its name is not specified.
   */
  String[] workers() default {};

  /**
   * @return Worker Task Queues to register this workflow implementation with. If Worker with the
   *     specified Task Queue is not defined in the application config, it will be created with a
   *     default config.
   */
  String[] taskQueues() default {};
}
