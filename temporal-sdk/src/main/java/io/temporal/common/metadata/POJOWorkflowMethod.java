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
import io.temporal.common.CronSchedule;
import io.temporal.common.MethodRetry;
import io.temporal.workflow.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

public final class POJOWorkflowMethod {

  private final WorkflowMethodType type;
  private final Method method;
  private final Type[] genericParameterTypes;
  private final Type genericReturnType;
  private final Optional<String> nameFromAnnotation;

  POJOWorkflowMethod(Method method) {
    this(method, method.getGenericParameterTypes(), method.getGenericReturnType());
  }

  POJOWorkflowMethod withGenericTypes(Type[] genericParameterTypes, Type genericReturnType) {
    return new POJOWorkflowMethod(method, genericParameterTypes, genericReturnType);
  }

  private POJOWorkflowMethod(Method method, Type[] genericParameterTypes, Type genericReturnType) {
    this.method = Objects.requireNonNull(method);
    WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
    QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
    SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
    UpdateMethod updateMethod = method.getAnnotation(UpdateMethod.class);
    UpdateValidatorMethod updateValidatorMethod = method.getAnnotation(UpdateValidatorMethod.class);

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
    if (updateValidatorMethod != null) {
      type = WorkflowMethodType.UPDATE_VALIDATOR;
      if (method.getReturnType() != Void.TYPE) {
        throw new IllegalArgumentException(
            "Method annotated with @UpdateValidatorMethod must have a void return type: " + method);
      }
      count++;
      name = updateValidatorMethod.updateName();
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
    this.genericParameterTypes = genericParameterTypes;
    this.genericReturnType = genericReturnType;
  }

  public WorkflowMethodType getType() {
    return type;
  }

  public Method getMethod() {
    return method;
  }

  public Class<?>[] getParameterTypes() {
    return method.getParameterTypes();
  }

  public Type[] getGenericParameterTypes() {
    return genericParameterTypes;
  }

  public Class<?> getReturnType() {
    return method.getReturnType();
  }

  public Type getGenericReturnType() {
    return genericReturnType;
  }

  public Optional<String> getNameFromAnnotation() {
    return nameFromAnnotation;
  }

  public MethodRetry getRetryAnnotation() {
    return method.getAnnotation(MethodRetry.class);
  }

  public CronSchedule getChronScheduleAnnotation() {
    return method.getAnnotation(CronSchedule.class);
  }

  public UpdateValidatorMethod getUpdateValidatorMethodAnnotation() {
    return method.getAnnotation(UpdateValidatorMethod.class);
  }

  public UpdateMethod getUpdateAnnotation() {
    return method.getAnnotation(UpdateMethod.class);
  }

  public SignalMethod getSignalAnnotation() {
    return method.getAnnotation(SignalMethod.class);
  }

  public Object invoke(Object obj, Object... args)
      throws IllegalAccessException, IllegalArgumentException, InvocationTargetException {
    return method.invoke(obj, args);
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

  @Override
  public String toString() {
    return method.toString();
  }
}
