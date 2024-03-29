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

package io.temporal.activity;

import io.temporal.common.converter.EncodedValues;

/**
 * Use DynamicActivity to implement any number of activity types dynamically. When an activity
 * implementation that extends DynamicActivity is registered it is called for any activity type
 * invocation that doesn't have an explicitly registered handler. Only one instance that implements
 * DynamicActivity per {@link io.temporal.worker.Worker} is allowed.
 *
 * <p>The DynamicActivity can be useful for integrations with existing libraries. For example, it
 * can be used to call some external HTTP API with each function exposed as a different activity
 * type.
 *
 * <p>Use {@link Activity#getExecutionContext()} to query information about the activity type that
 * should be implemented dynamically.
 *
 * @see io.temporal.workflow.DynamicWorkflow
 */
public interface DynamicActivity {
  Object execute(EncodedValues args);
}
