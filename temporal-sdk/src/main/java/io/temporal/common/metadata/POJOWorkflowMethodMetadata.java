package io.temporal.common.metadata;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/** Metadata of a single workflow method. */
public final class POJOWorkflowMethodMetadata {

  private final POJOWorkflowMethod workflowMethod;
  private final String name;
  private final String description;
  private final Class<?> workflowInterface;

  POJOWorkflowMethodMetadata(POJOWorkflowMethod methodMetadata, Class<?> workflowInterface) {
    this.workflowMethod = Objects.requireNonNull(methodMetadata);
    if (workflowMethod.getType() == WorkflowMethodType.NONE) {
      throw new IllegalArgumentException(
          "Method \""
              + methodMetadata.getMethod().getName()
              + "\" is not annotated with @WorkflowMethod, @SignalMethod @QueryMethod, @UpdateMethod, or @UpdateValidatorMethod");
    }

    this.workflowInterface = Objects.requireNonNull(workflowInterface);
    Optional<String> nameFromAnnotation = workflowMethod.getNameFromAnnotation();
    if (workflowMethod.getType() == WorkflowMethodType.WORKFLOW) {
      this.name = nameFromAnnotation.orElse(workflowInterface.getSimpleName());
    } else {
      this.name = nameFromAnnotation.orElse(methodMetadata.getMethod().getName());
    }
    this.description = workflowMethod.getDescriptionFromAnnotation().orElse("");
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

  public String getDescription() {
    return description;
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
