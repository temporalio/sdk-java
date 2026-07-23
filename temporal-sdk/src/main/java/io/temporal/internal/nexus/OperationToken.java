package io.temporal.internal.nexus;

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
  private final String workflowId;

  @JsonProperty("rid")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  // only set for updates and activities
  private final String runId;

  @JsonProperty("uid")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  // only set for updates
  private final String updateId;

  public OperationToken(
      @JsonProperty("t") Integer type,
      @JsonProperty("ns") String namespace,
      @JsonProperty("wid") String workflowId,
      @JsonProperty("rid") String runId,
      @JsonProperty("uid") String updateId,
      @JsonProperty("v") Integer version) {
    this.type = OperationTokenType.fromValue(type);
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.runId = runId;
    this.updateId = updateId;
    this.version = version;
  }

  /** Generate a token for a workflow run operation */
  public OperationToken(OperationTokenType type, String namespace, String workflowId) {
    this.type = type;
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.version = null;
    this.runId = null;
    this.updateId = null;
  }

  /** Generate a token for a workflow update operation */
  public OperationToken(String namespace, String workflowId, String runId, String updateId) {
    this.type = OperationTokenType.WORKFLOW_UPDATE;
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.runId = runId;
    this.updateId = updateId;
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

  public String getUpdateId() {
    return updateId;
  }

  public String getRunId() {
    return runId;
  }
}
