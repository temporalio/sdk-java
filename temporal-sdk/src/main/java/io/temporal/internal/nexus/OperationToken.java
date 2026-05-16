package io.temporal.internal.nexus;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Deserialized representation of a Nexus operation token. */
public class OperationToken {
  @JsonProperty("v")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final Integer version;

  @JsonProperty("t")
  private final OperationTokenType type;

  @JsonProperty("ns")
  private final String namespace;

  @JsonProperty("wid")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final String workflowId;

  @JsonProperty("aid")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final String activityId;

  @JsonCreator
  public OperationToken(
      @JsonProperty("t") Integer type,
      @JsonProperty("ns") String namespace,
      @JsonProperty("wid") String workflowId,
      @JsonProperty("aid") String activityId,
      @JsonProperty("v") Integer version) {
    this.type = OperationTokenType.fromValue(type);
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = activityId;
    this.version = version;
  }

  public OperationToken(OperationTokenType type, String namespace, String workflowId) {
    this.type = type;
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = null;
    this.version = null;
  }

  public OperationToken(
      OperationTokenType type, String namespace, String workflowId, String activityId) {
    this.type = type;
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = activityId;
    this.version = null;
  }

  public Integer getVersion() {
    return version;
  }

  public OperationTokenType getType() {
    return type;
  }

  public String getNamespace() {
    return namespace;
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public String getActivityId() {
    return activityId;
  }
}
