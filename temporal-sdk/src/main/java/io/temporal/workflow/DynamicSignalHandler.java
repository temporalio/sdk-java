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
 */
public interface DynamicSignalHandler {
  void handle(String signalName, EncodedValues args);
}
