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

package com.uber.cadence.worker;

import static com.uber.cadence.worker.NonDeterministicWorkflowPolicy.BlockWorkflow;

import java.util.Objects;

public final class WorkflowImplementationOptions {

  public static final class Builder {

    private NonDeterministicWorkflowPolicy nonDeterministicWorkflowPolicy = BlockWorkflow;

    /**
     * Optional: Sets how decision worker deals with non-deterministic history events (presumably
     * arising from non-deterministic workflow definitions or non-backward compatible workflow
     * definition changes). default: BlockWorkflow, which just logs error but reply nothing back to
     * server.
     */
    public Builder setNonDeterministicWorkflowPolicy(
        NonDeterministicWorkflowPolicy nonDeterministicWorkflowPolicy) {
      this.nonDeterministicWorkflowPolicy = Objects.requireNonNull(nonDeterministicWorkflowPolicy);
      return this;
    }

    public WorkflowImplementationOptions build() {
      return new WorkflowImplementationOptions(nonDeterministicWorkflowPolicy);
    }
  }

  private final NonDeterministicWorkflowPolicy nonDeterministicWorkflowPolicy;

  public WorkflowImplementationOptions(
      NonDeterministicWorkflowPolicy nonDeterministicWorkflowPolicy) {
    this.nonDeterministicWorkflowPolicy = nonDeterministicWorkflowPolicy;
  }

  public NonDeterministicWorkflowPolicy getNonDeterministicWorkflowPolicy() {
    return nonDeterministicWorkflowPolicy;
  }

  @Override
  public String toString() {
    return "WorkflowImplementationOptions{"
        + "nonDeterministicWorkflowPolicy="
        + nonDeterministicWorkflowPolicy
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowImplementationOptions that = (WorkflowImplementationOptions) o;
    return nonDeterministicWorkflowPolicy == that.nonDeterministicWorkflowPolicy;
  }

  @Override
  public int hashCode() {
    return Objects.hash(nonDeterministicWorkflowPolicy);
  }
}
