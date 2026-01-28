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
