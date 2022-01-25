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
import io.temporal.activity.LocalActivityOptions;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
    private Map<String, LocalActivityOptions> localActivityOptions;
    private LocalActivityOptions defaultLocalActivityOptions;

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

    /**
     * Set individual local activity options per activityType. Will be merged with the map from
     * {@link io.temporal.workflow.Workflow#newLocalActivityStub(Class, LocalActivityOptions, Map)}
     * which has highest precedence.
     *
     * @param localActivityOptions map from activityType to ActivityOptions
     */
    public Builder setLocalActivityOptions(Map<String, LocalActivityOptions> localActivityOptions) {
      this.localActivityOptions = new HashMap<>(Objects.requireNonNull(localActivityOptions));
      return this;
    }

    /**
     * These local activity options have the lowest precedence across all local activity options.
     * Will be overwritten entirely by {@link
     * io.temporal.workflow.Workflow#newLocalActivityStub(Class, LocalActivityOptions)} and then by
     * the individual local activity options if any are set through {@link
     * #setLocalActivityOptions(Map)}
     *
     * @param defaultLocalActivityOptions ActivityOptions for all activities in the workflow.
     */
    public Builder setDefaultLocalActivityOptions(
        LocalActivityOptions defaultLocalActivityOptions) {
      this.defaultLocalActivityOptions = Objects.requireNonNull(defaultLocalActivityOptions);
      return this;
    }

    public WorkflowImplementationOptions build() {
      return new WorkflowImplementationOptions(
          failWorkflowExceptionTypes == null ? new Class[0] : failWorkflowExceptionTypes,
          activityOptions == null ? null : activityOptions,
          defaultActivityOptions,
          localActivityOptions == null ? null : localActivityOptions,
          defaultLocalActivityOptions);
    }
  }

  private final Class<? extends Throwable>[] failWorkflowExceptionTypes;
  private final @Nullable Map<String, ActivityOptions> activityOptions;
  private final ActivityOptions defaultActivityOptions;
  private final @Nullable Map<String, LocalActivityOptions> localActivityOptions;
  private final LocalActivityOptions defaultLocalActivityOptions;

  public WorkflowImplementationOptions(
      Class<? extends Throwable>[] failWorkflowExceptionTypes,
      @Nullable Map<String, ActivityOptions> activityOptions,
      ActivityOptions defaultActivityOptions,
      @Nullable Map<String, LocalActivityOptions> localActivityOptions,
      LocalActivityOptions defaultLocalActivityOptions) {
    this.failWorkflowExceptionTypes = failWorkflowExceptionTypes;
    this.activityOptions = activityOptions;
    this.defaultActivityOptions = defaultActivityOptions;
    this.localActivityOptions = localActivityOptions;
    this.defaultLocalActivityOptions = defaultLocalActivityOptions;
  }

  public Class<? extends Throwable>[] getFailWorkflowExceptionTypes() {
    return failWorkflowExceptionTypes;
  }

  public @Nonnull Map<String, ActivityOptions> getActivityOptions() {
    return activityOptions != null
        ? Collections.unmodifiableMap(activityOptions)
        : Collections.emptyMap();
  }

  public ActivityOptions getDefaultActivityOptions() {
    return defaultActivityOptions;
  }

  public @Nonnull Map<String, LocalActivityOptions> getLocalActivityOptions() {
    return localActivityOptions != null
        ? Collections.unmodifiableMap(localActivityOptions)
        : Collections.emptyMap();
  }

  public LocalActivityOptions getDefaultLocalActivityOptions() {
    return defaultLocalActivityOptions;
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
        + ", localActivityOptions="
        + localActivityOptions
        + ", defaultLocalActivityOptions="
        + defaultLocalActivityOptions
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowImplementationOptions that = (WorkflowImplementationOptions) o;
    return Arrays.equals(failWorkflowExceptionTypes, that.failWorkflowExceptionTypes)
        && Objects.equals(activityOptions, that.activityOptions)
        && Objects.equals(defaultActivityOptions, that.defaultActivityOptions)
        && Objects.equals(localActivityOptions, that.localActivityOptions)
        && Objects.equals(defaultLocalActivityOptions, that.defaultLocalActivityOptions);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(
            activityOptions,
            defaultActivityOptions,
            localActivityOptions,
            defaultLocalActivityOptions);
    result = 31 * result + Arrays.hashCode(failWorkflowExceptionTypes);
    return result;
  }
}
