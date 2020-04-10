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

package io.temporal.worker;

public enum WorkflowErrorPolicy {

  /**
   * If workflow code throws an {@link Error} the workflow progress blocks problem is fixed usually
   * by a worker deployment rollback.
   *
   * <p>Note that in case of non deterministic code which is the most frequently caused by a code
   * change not protected by {@link io.temporal.workflow.Workflow#getVersion(String, int, int)} the
   * Error is thrown by the framework.
   */
  BlockWorkflow,

  /**
   * If workflow code throws an {@link Error} the workflow is immediately failed.
   *
   * <p>Note that in case of non deterministic code which is the most frequently caused by a code
   * change not protected by {@link io.temporal.workflow.Workflow#getVersion(String, int, int)} the
   * Error is thrown by the framework.
   *
   * <p>WARNING: enabling this in production can cause all open workflows to fail on a single bug or
   * bad deployment.
   */
  FailWorkflow
}
