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

package io.temporal.workflow;

import io.temporal.common.converter.EncodedValues;

/**
 * Use DynamicSignalHandler to process any signal dynamically. This is useful for a library level
 * code and implementation of DSLs.
 *
 * <p>Use {@link Workflow#registerListener(Object)} to register an implementation of the
 * DynamicSignalListener. Only one such listener can be registered per workflow execution.
 *
 * <p>When registered any signals which don't have a specific handler will be delivered to it.
 *
 * @see DynamicQueryHandler
 * @see DynamicWorkflow
 * @see DynamicUpdateHandler
 */
public interface DynamicSignalHandler {
  void handle(String signalName, EncodedValues args);

  /** Returns the actions taken if a workflow exits with a running instance of this handler. */
  default HandlerUnfinishedPolicy getUnfinishedPolicy(String signalName) {
    return HandlerUnfinishedPolicy.WARN_AND_ABANDON;
  }

  /** Short description of the Update handler. */
  default String getDescription() {
    return "Dynamic signal handler";
  }
}
