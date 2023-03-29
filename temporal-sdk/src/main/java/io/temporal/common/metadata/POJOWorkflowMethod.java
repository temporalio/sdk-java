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

import com.google.common.base.Strings;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowMethod;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

final class POJOWorkflowMethod {

  private final WorkflowMethodType type;
  private final Method method;
  private final Optional<String> nameFromAnnotation;

  POJOWorkflowMethod(Method method) {
    this.method = Objects.requireNonNull(method);
    WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
    QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
    SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
    UpdateMethod updateMethod = method.getAnnotation(UpdateMethod.class);

    int count = 0;
    WorkflowMethodType type = null;
    String name = null;
    if (workflowMethod != null) {
      type = WorkflowMethodType.WORKFLOW;
      count++;
      name = workflowMethod.name();
    }
    if (signalMethod != null) {
      type = WorkflowMethodType.SIGNAL;
      if (method.getReturnType() != Void.TYPE) {
        throw new IllegalArgumentException(
            "Method annotated with @SignalMethod must have void return type: " + method);
      }
      count++;
      name = signalMethod.name();
    }
    if (queryMethod != null) {
      type = WorkflowMethodType.QUERY;
      if (method.getReturnType() == Void.TYPE) {
        throw new IllegalArgumentException(
            "Method annotated with @QueryMethod cannot have void return type: " + method);
      }
      count++;
      name = queryMethod.name();
    }
    if (updateMethod != null) {
      type = WorkflowMethodType.UPDATE;
      count++;
      name = updateMethod.name();
    }
    if (count == 0) {
      type = WorkflowMethodType.NONE;
    } else if (count > 1) {
      throw new IllegalArgumentException(
          method
              + " must contain exactly one annotation "
              + "of @WorkflowMethod, @QueryMethod or @SignalMethod");
    }
    if (Strings.isNullOrEmpty(name)) {
      this.nameFromAnnotation = Optional.empty();
    } else {
      this.nameFromAnnotation = Optional.of(name);
    }
    this.type = Objects.requireNonNull(type);
  }

  public WorkflowMethodType getType() {
    return type;
  }

  public Method getMethod() {
    return method;
  }

  public Optional<String> getNameFromAnnotation() {
    return nameFromAnnotation;
  }

  /** Compare and hash on method only. */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    POJOWorkflowMethod that = (POJOWorkflowMethod) o;
    return type == that.type && com.google.common.base.Objects.equal(method, that.method);
  }

  /** Compare and hash on method only. */
  @Override
  public int hashCode() {
    return com.google.common.base.Objects.hashCode(method);
  }
}
