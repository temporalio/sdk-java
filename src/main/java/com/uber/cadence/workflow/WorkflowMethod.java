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

import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.client.WorkflowOptions;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the method is a workflow method. Workflow method is executed when workflow is
 * started. Workflow completes when workflow method returns. This annotation applies only to
 * workflow interface methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WorkflowMethod {
  /** Name of the workflow type. Default is {short class name}::{method name} */
  String name() default "";

  /**
   * Workflow ID to use. Default is random UUID. Specifying workflow in the annotation makes sense
   * only for singleton workflows that would ever have one instance per type running. Make sure that
   * {@link WorkflowIdReusePolicy} is AllowDuplicate in this case.
   */
  String workflowId() default "";

  /**
   * How to react if there is completed workflow with the same ID.
   * <li>
   *
   *     <ul>
   *       AllowDuplicate - Always start a new run
   * </ul>
   *
   * <ul>
   *   RejectDuplicate - Never allow a second run
   * </ul>
   *
   * <ul>
   *   AllowDuplicateFailedOnly - Allow only if workflow didn't complete successfully.
   * </ul>
   *
   * Default is AllowDuplicateFailedOnly.
   */
  WorkflowIdReusePolicy workflowIdReusePolicy() default
      WorkflowIdReusePolicy.AllowDuplicateFailedOnly;

  /**
   * Maximum workflow execution time. Must be specified either through {@link
   * WorkflowMethod#executionStartToCloseTimeoutSeconds()} or {@link
   * WorkflowOptions#getExecutionStartToCloseTimeout()}.
   */
  int executionStartToCloseTimeoutSeconds() default 0;

  /**
   * Maximum execution time of a single workflow task. Workflow tasks are reactions to a new events
   * in the workflow history. Usually they are pretty short. Default is 10 seconds. Maximum allowed
   * value is 1 minute.
   */
  int taskStartToCloseTimeoutSeconds() default 10;

  /**
   * Task list to use when delivering workflow tasks. Must be specified either through this
   * annotation or through WorkflowOptions.
   */
  String taskList() default "";
}
