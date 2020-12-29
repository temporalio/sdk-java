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
 * Use DynamicWorkflow to implement any number of workflow types dynamically. When a workflow
 * implementation type that extends DynamicWorkflow is registered, it is used to implement any
 * workflow type that is not implicitly registered with the {@link io.temporal.worker.Worker}. Only
 * one type that implements DynamicWorkflow per worker is allowed.
 *
 * <p>The main use case for DynamicWorkflow is an implementation of custom Domain Specific Languages
 * (DSLs). A single implementation can implement a workflow type which definition is dynamically
 * loaded from some external source.
 *
 * <p>Use {@link Workflow#getInfo()} to query information about the workflow type that should be
 * implemented dynamically.
 *
 * <p>Use {@link Workflow#registerListener(Object)} to register signal and query listeners. Consider
 * using {@link DynamicSignalHandler} and {@link DynamicQueryHandler} to implement handlers that can
 * support any signal or query type dynamically.
 *
 * <p>All the determinism rules still apply to workflows that implement this interface.
 *
 * @see io.temporal.activity.DynamicActivity
 */
public interface DynamicWorkflow {
  Object execute(EncodedValues args);
}
