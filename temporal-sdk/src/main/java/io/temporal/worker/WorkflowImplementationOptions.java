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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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
    private ActivityOptions defaultActivityOptions;

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
    @SafeVarargs
    public final Builder setFailWorkflowExceptionTypes(
        Class<? extends Throwable>... failWorkflowExceptionTypes) {
      this.failWorkflowExceptionTypes = failWorkflowExceptionTypes;
      return this;
    }

    /**
     * Set individual activity options per activityType. Will be merged with the map from {@link
     * io.temporal.workflow.Workflow#newActivityStub(Class, ActivityOptions, Map)} which has highest
     * precedence.
     *
     * @param activityOptions map from activityType to ActivityOptions
     */
    public Builder setActivityOptions(Map<String, ActivityOptions> activityOptions) {
      this.activityOptions = new HashMap<>(Objects.requireNonNull(activityOptions));
      return this;
    }

    /**
     * These activity options have the lowest precedence across all activity options. Will be
     * overwritten entirely by {@link io.temporal.workflow.Workflow#newActivityStub(Class,
     * ActivityOptions)} and then by the individual activity options if any are set through {@link
     * #setActivityOptions(Map)}
     *
     * @param defaultActivityOptions ActivityOptions for all activities in the workflow.
     */
    public Builder setDefaultActivityOptions(ActivityOptions defaultActivityOptions) {
      this.defaultActivityOptions = Objects.requireNonNull(defaultActivityOptions);
      return this;
    }

    public WorkflowImplementationOptions build() {
      return new WorkflowImplementationOptions(
          failWorkflowExceptionTypes == null ? new Class[0] : failWorkflowExceptionTypes,
          activityOptions == null ? new HashMap<>() : activityOptions,
          defaultActivityOptions);
    }
  }

  private final Class<? extends Throwable>[] failWorkflowExceptionTypes;
  private final Map<String, ActivityOptions> activityOptions;
  private final ActivityOptions defaultActivityOptions;

  public WorkflowImplementationOptions(
      Class<? extends Throwable>[] failWorkflowExceptionTypes,
      Map<String, ActivityOptions> activityOptions,
      ActivityOptions defaultActivityOptions) {
    this.failWorkflowExceptionTypes = failWorkflowExceptionTypes;
    this.activityOptions = activityOptions;
    this.defaultActivityOptions = defaultActivityOptions;
  }

  public Class<? extends Throwable>[] getFailWorkflowExceptionTypes() {
    return failWorkflowExceptionTypes;
  }

  public Map<String, ActivityOptions> getActivityOptions() {
    return activityOptions;
  }

  public ActivityOptions getDefaultActivityOptions() {
    return defaultActivityOptions;
  }

  @Override
  public String toString() {
    return "WorkflowImplementationOptions{"
        + "failWorkflowExceptionTypes="
        + Arrays.toString(failWorkflowExceptionTypes)
        + ", activityOptions="
        + activityOptions
        + ", defaultActivityOptions="
        + defaultActivityOptions
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowImplementationOptions that = (WorkflowImplementationOptions) o;
    return Arrays.equals(failWorkflowExceptionTypes, that.failWorkflowExceptionTypes)
        && Objects.equals(activityOptions, that.activityOptions)
        && Objects.equals(defaultActivityOptions, that.defaultActivityOptions);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(activityOptions, defaultActivityOptions);
    result = 31 * result + Arrays.hashCode(failWorkflowExceptionTypes);
    return result;
  }
}
