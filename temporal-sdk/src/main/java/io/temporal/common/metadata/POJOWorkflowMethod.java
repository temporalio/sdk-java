package io.temporal.common.metadata;

import com.google.common.base.Strings;
import io.temporal.workflow.*;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

final class POJOWorkflowMethod {

  private final WorkflowMethodType type;
  private final Method method;
  private final Optional<String> nameFromAnnotation;
  private final Optional<String> descriptionFromAnnotation;

  POJOWorkflowMethod(Method method) {
    this.method = Objects.requireNonNull(method);
    WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
    QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
    SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
    UpdateMethod updateMethod = method.getAnnotation(UpdateMethod.class);
    UpdateValidatorMethod updateValidatorMethod = method.getAnnotation(UpdateValidatorMethod.class);

    int count = 0;
    WorkflowMethodType type = null;
    String name = null;
    String description = null;
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
      description = signalMethod.description();
    }
    if (queryMethod != null) {
      type = WorkflowMethodType.QUERY;
      if (method.getReturnType() == Void.TYPE) {
        throw new IllegalArgumentException(
            "Method annotated with @QueryMethod cannot have void return type: " + method);
      }
      count++;
      name = queryMethod.name();
      description = queryMethod.description();
    }
    if (updateMethod != null) {
      type = WorkflowMethodType.UPDATE;
      count++;
      name = updateMethod.name();
      description = updateMethod.description();
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
              + "of @WorkflowMethod, @QueryMethod @UpdateMethod or @SignalMethod");
    }
    if (Strings.isNullOrEmpty(name)) {
      this.nameFromAnnotation = Optional.empty();
    } else {
      this.nameFromAnnotation = Optional.of(name);
    }

    if (Strings.isNullOrEmpty(description)) {
      this.descriptionFromAnnotation = Optional.empty();
    } else {
      this.descriptionFromAnnotation = Optional.of(description);
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

  public Optional<String> getDescriptionFromAnnotation() {
    return descriptionFromAnnotation;
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
