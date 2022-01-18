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

package io.temporal.internal.testservice;

import java.util.Objects;

class WorkflowId {

  private final String namespace;
  private final String workflowId;

  public WorkflowId(String namespace, String workflowId) {
    this.namespace = Objects.requireNonNull(namespace);
    this.workflowId = Objects.requireNonNull(workflowId);
  }

  public String getNamespace() {
    return namespace;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || !(o instanceof WorkflowId)) {
      return false;
    }

    WorkflowId that = (WorkflowId) o;

    if (!namespace.equals(that.namespace)) {
      return false;
    }
    return workflowId.equals(that.workflowId);
  }

  @Override
  public int hashCode() {
    int result = namespace.hashCode();
    result = 31 * result + workflowId.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "WorkflowId{"
        + "namespace='"
        + namespace
        + '\''
        + ", workflowId='"
        + workflowId
        + '\''
        + '}';
  }
}
