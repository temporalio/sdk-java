/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.activity;

import io.temporal.common.converter.EncodedValues;

/**
 * Use DynamicActivity to implement any number of activity types dynamically. When an activity
 * implementation that extends DynamicActivity is registered it is called for any activity type
 * invocation that doesn't have an explicitly registered handler. Only one instance that implements
 * DynamicActivity per {@link io.temporal.worker.Worker} is allowed.
 *
 * <p>The DynamicActivity can be useful for integrations with existing libraries.
 *
 * <p>Use {@link Activity#getExecutionContext()} to query information about the activity type that
 * should be implemented dynamically.
 *
 * @see io.temporal.workflow.DynamicWorkflow
 */
public interface DynamicActivity {
  Object execute(EncodedValues args);
}
