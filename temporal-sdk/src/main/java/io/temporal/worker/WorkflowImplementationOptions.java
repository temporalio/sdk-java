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

import io.temporal.activity.ActivityOptions;
import java.util.Arrays;
import java.util.Map;

public final class WorkflowImplementationOptions {

  private static final WorkflowImplementationOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = WorkflowImplementationOptions.newBuilder().build();
  }

  public static WorkflowImplementationOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {

    private Class<? extends Throwable>[] failWorkflowExceptionTypes;
    private Map<String, ActivityOptions> activityOptions;
    private Map<String, Map<String, ActivityOptions>> activityToMethodOptions;

    private Builder() {}

    /**
     * Optional: Sets how workflow worker deals with exceptions thrown from the workflow code which
     * include non-deterministic history events (presumably arising from non-deterministic workflow
     * definitions or non-backward compatible workflow definition changes).
     *
     * <p>The default behavior is to fail workflow on {@link io.temporal.failure.TemporalFailure} or
     * any of its subclasses. Any other exceptions thrown from the workflow code are treated as bugs
     * that can be fixed by a new deployment. So workflow is not failed, but it stuck in a retry
     * loop trying to execute the code that led to the unexpected exception.
     *
     * <p>This option allows to specify specific exception types which should lead to workflow
     * failure instead of blockage. Any exception that extends the configured type considered
     * matched. For example to fail workflow on any exception pass {@link Throwable} class to this
     * method.
     */
    public Builder setFailWorkflowExceptionTypes(
        Class<? extends Throwable>... failWorkflowExceptionTypes) {
      this.failWorkflowExceptionTypes = failWorkflowExceptionTypes;
      return this;
    }

    public Builder setActivityOptions(Map<String, ActivityOptions> activityOptions) {
      this.activityOptions = activityOptions;
      return this;
    }

    public Builder setActivityMethodOptions(
        Map<String, Map<String, ActivityOptions>> activityToMethodOptions) {
      this.activityToMethodOptions = activityToMethodOptions;
      return this;
    }

    public WorkflowImplementationOptions build() {
      return new WorkflowImplementationOptions(
          failWorkflowExceptionTypes == null ? new Class[0] : failWorkflowExceptionTypes);
    }
  }

  private final Class<? extends Throwable>[] failWorkflowExceptionTypes;

  public WorkflowImplementationOptions(Class<? extends Throwable>[] failWorkflowExceptionTypes) {
    this.failWorkflowExceptionTypes = failWorkflowExceptionTypes;
  }

  public Class<? extends Throwable>[] getFailWorkflowExceptionTypes() {
    return failWorkflowExceptionTypes;
  }

  @Override
  public String toString() {
    return "WorkflowImplementationOptions{"
        + "failWorkflowExceptionTypes="
        + Arrays.toString(failWorkflowExceptionTypes)
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowImplementationOptions that = (WorkflowImplementationOptions) o;
    return Arrays.equals(failWorkflowExceptionTypes, that.failWorkflowExceptionTypes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(failWorkflowExceptionTypes);
  }
}
