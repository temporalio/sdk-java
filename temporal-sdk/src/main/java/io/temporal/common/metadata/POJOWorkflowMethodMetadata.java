/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.common.metadata;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/** Metadata of a single workflow method. */
public final class POJOWorkflowMethodMetadata {

  private final POJOWorkflowMethod workflowMethod;
  private final String name;
  private final Class<?> workflowInterface;

  POJOWorkflowMethodMetadata(POJOWorkflowMethod methodMetadata, Class<?> workflowInterface) {
    this.workflowMethod = Objects.requireNonNull(methodMetadata);
    if (workflowMethod.getType() == WorkflowMethodType.NONE) {
      throw new IllegalArgumentException(
          "Method \""
              + methodMetadata.getMethod().getName()
              + "\" is not annotated with @WorkflowMethod, @SignalMethod or @QueryMethod");
    }

    this.workflowInterface = Objects.requireNonNull(workflowInterface);
    Optional<String> nameFromAnnotation = workflowMethod.getNameFromAnnotation();
    if (workflowMethod.getType() == WorkflowMethodType.WORKFLOW) {
      this.name = nameFromAnnotation.orElse(workflowInterface.getSimpleName());
    } else {
      this.name = nameFromAnnotation.orElse(methodMetadata.getMethod().getName());
    }
  }

  public WorkflowMethodType getType() {
    return workflowMethod.getType();
  }

  /**
   * The semantics of the name depends on the value of {@link #getType()}. It is signal name for
   * {@link WorkflowMethodType#SIGNAL}, query type for {@link WorkflowMethodType#QUERY} and workflow
   * type for {@link WorkflowMethodType#WORKFLOW}.
   */
  public String getName() {
    return name;
  }

  public Method getWorkflowMethod() {
    return workflowMethod.getMethod();
  }

  public Class<?> getWorkflowInterface() {
    return workflowInterface;
  }

  /** Compare and hash based on method and the interface type only. */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    POJOWorkflowMethodMetadata that = (POJOWorkflowMethodMetadata) o;
    return com.google.common.base.Objects.equal(workflowMethod, that.workflowMethod)
        && com.google.common.base.Objects.equal(workflowInterface, that.workflowInterface);
  }

  /** Compare and hash based on method and the interface type only. */
  @Override
  public int hashCode() {
    return com.google.common.base.Objects.hashCode(workflowMethod, workflowInterface);
  }
}
