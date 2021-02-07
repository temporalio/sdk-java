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

package io.temporal.common.metadata;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

public final class POJOWorkflowMethodMetadata {

  private final POJOWorkflowMethod workflowMethod;
  private final String name;
  private final Class<?> interfaceType;

  POJOWorkflowMethodMetadata(POJOWorkflowMethod methodMetadata, Class<?> interfaceType) {
    this.workflowMethod = Objects.requireNonNull(methodMetadata);
    if (workflowMethod.getType() == WorkflowMethodType.NONE) {
      throw new IllegalArgumentException(
          "Method \""
              + methodMetadata.getMethod().getName()
              + "\" is not annotated with @WorkflowMethod, @SignalMethod or @QueryMethod");
    }

    this.interfaceType = Objects.requireNonNull(interfaceType);
    Optional<String> nameFromAnnotation = workflowMethod.getNameFromAnnotation();
    if (workflowMethod.getType() == WorkflowMethodType.WORKFLOW) {
      this.name = nameFromAnnotation.orElse(interfaceType.getSimpleName());
    } else {
      this.name = nameFromAnnotation.orElse(methodMetadata.getMethod().getName());
    }
  }

  public WorkflowMethodType getType() {
    return workflowMethod.getType();
  }

  public String getName() {
    return name;
  }

  public Method getWorkflowMethod() {
    return workflowMethod.getMethod();
  }

  /** Compare and hash based on method and the interface type only. */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    POJOWorkflowMethodMetadata that = (POJOWorkflowMethodMetadata) o;
    return com.google.common.base.Objects.equal(workflowMethod, that.workflowMethod)
        && com.google.common.base.Objects.equal(interfaceType, that.interfaceType);
  }

  /** Compare and hash based on method and the interface type only. */
  @Override
  public int hashCode() {
    return com.google.common.base.Objects.hashCode(workflowMethod, interfaceType);
  }
}
