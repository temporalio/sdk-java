/*
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

package com.uber.cadence.workflow;

import com.uber.cadence.WorkflowExecution;
import java.lang.reflect.Type;

/**
 * Supports starting and signalling child workflows by the name and list of arguments. This is
 * useful when a child workflow type is not known at the compile time and to call child workflows in
 * other languages.
 *
 * @see Workflow#newChildWorkflowStub(Class)
 */
public interface ChildWorkflowStub {

  String getWorkflowType();

  Promise<WorkflowExecution> getExecution();

  ChildWorkflowOptions getOptions();

  <R> R execute(Class<R> resultClass, Object... args);

  <R> R execute(Class<R> resultClass, Type resultType, Object... args);

  <R> Promise<R> executeAsync(Class<R> resultClass, Object... args);

  <R> Promise<R> executeAsync(Class<R> resultClass, Type resultType, Object... args);

  void signal(String signalName, Object... args);
}
