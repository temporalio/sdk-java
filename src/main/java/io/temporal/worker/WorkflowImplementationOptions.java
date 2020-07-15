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

import static io.temporal.worker.WorkflowErrorPolicy.BlockWorkflow;

import java.util.Objects;

public final class WorkflowImplementationOptions {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {

    private WorkflowErrorPolicy workflowErrorPolicy = BlockWorkflow;

    private Builder() {}

    /**
     * Optional: Sets how workflow worker deals with Error thrown from the workflow code which
     * include non-deterministic history events (presumably arising from non-deterministic workflow
     * definitions or non-backward compatible workflow definition changes).
     *
     * <p>default: BlockWorkflow which lets fixing the problem (frequently by rollback) without
     * failing open workflows.
     */
    public Builder setWorkflowErrorPolicy(WorkflowErrorPolicy workflowErrorPolicy) {
      this.workflowErrorPolicy = Objects.requireNonNull(workflowErrorPolicy);
      return this;
    }

    public WorkflowImplementationOptions build() {
      return new WorkflowImplementationOptions(workflowErrorPolicy);
    }
  }

  private final WorkflowErrorPolicy workflowErrorPolicy;

  public WorkflowImplementationOptions(WorkflowErrorPolicy workflowErrorPolicy) {
    this.workflowErrorPolicy = workflowErrorPolicy;
  }

  public WorkflowErrorPolicy getWorkflowErrorPolicy() {
    return workflowErrorPolicy;
  }

  @Override
  public String toString() {
    return "WorkflowImplementationOptions{" + "workflowErrorPolicy=" + workflowErrorPolicy + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowImplementationOptions that = (WorkflowImplementationOptions) o;
    return workflowErrorPolicy == that.workflowErrorPolicy;
  }

  @Override
  public int hashCode() {
    return Objects.hash(workflowErrorPolicy);
  }
}
