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
 * Use DynamicQueryHandler to process any query dynamically. This is useful for a library level code
 * and implementation of DSLs.
 *
 * <p>Use {@link Workflow#registerListener(Object)} to register an implementation of the
 * DynamicQueryListener. Only one such listener can be registered per workflow execution.
 *
 * <p>When registered any queries which don't have a specific handler will be delivered to it.
 *
 * @see DynamicSignalHandler
 * @see DynamicWorkflow
 * @see DynamicUpdateHandler
 */
public interface DynamicQueryHandler {
  Object handle(String queryType, EncodedValues args);

  /** Short description of the Query handler. */
  default String getDescription() {
    return "Dynamic query handler";
  }
}
