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

package io.temporal.workflow.unsafe;

import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.sync.WorkflowInternal;

/**
 * While {@link io.temporal.workflow.Workflow} contains methods exposing the main Temporal Workflow
 * API that is safe to use by majority of our users, this class contains the part of the Workflow
 * API that is discouraged to be used without careful consideration. While APIs in this class may be
 * required for some legitimate scenarios, most users shouldn't be using them. Relying on these
 * methods without careful consideration and understanding of limitations of each method may lead to
 * an unexpected behavior and mistakes.
 *
 * <p>Please reference a documentation for each specific method for more context why this method is
 * considered unsafe to be a part of the main Workflow API.
 */
public final class WorkflowUnsafe {

  /**
   * <b>Warning!</b> <br>
   * The only reasonable usage for this method is writing a library code that has to be used in both
   * workflow and non-workflow code (like an Activity or non-Temporal codebase). This usage is
   * generally discouraged, because mixing of workflow and non-workflow code is error-prone, because
   * workflow code requires considerations for determinism. Writing shared code that is designed to
   * be called from both workflow and non-workflow context may lead to leaking of the code not
   * written with Workflow limitations in mind into Workflow method implementation leading to
   * non-deterministic Workflow implementations.
   *
   * @return true if the current execution happens as a part of workflow method and in a workflow
   *     thread context.
   */
  public static boolean isWorkflowThread() {
    return WorkflowThreadMarker.isWorkflowThread();
  }

  /**
   * <b>Warning!</b> <br>
   * Never make workflow logic depend on this flag as it is going to break determinism. The only
   * reasonable uses for this flag is deduping external never failing side effects like logging or
   * metric reporting.
   *
   * @return true if workflow code is being replayed. This method always returns false if called
   *     from a non workflow thread.
   */
  public static boolean isReplaying() {
    return WorkflowInternal.isReplaying();
  }

  /** Prohibit instantiation. */
  private WorkflowUnsafe() {}
}
