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

  @JsonProperty("rid")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final String runId;

  @JsonProperty("uid")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private final String updateId;

  @JsonCreator
  public OperationToken(
      @JsonProperty("t") Integer type,
      @JsonProperty("ns") String namespace,
      @JsonProperty("wid") String workflowId,
      @JsonProperty("aid") String activityId,
      @JsonProperty("rid") String runId,
      @JsonProperty("uid") String updateId,
      @JsonProperty("v") Integer version) {
    this.type = OperationTokenType.fromValue(type);
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = activityId;
    this.runId = runId;
    this.updateId = updateId;
    this.version = version;
  }

  /** Generate a token for a workflow run operation */
  public OperationToken(OperationTokenType type, String namespace, String workflowId) {
    this.type = type;
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = null;
    this.runId = null;
    this.updateId = null;
    this.version = null;
  }

  public OperationToken(
      OperationTokenType type, String namespace, String workflowId, String activityId) {
    this(type, namespace, workflowId, activityId, null);
  }

  public OperationToken(
      OperationTokenType type,
      String namespace,
      String workflowId,
      String activityId,
      String runId) {
    this.type = type;
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = activityId;
    this.runId = runId;
    this.updateId = null;
    this.version = null;
  }

  /** Generate a token for a workflow update operation */
  public OperationToken(String namespace, String workflowId, String runId, String updateId) {
    this.type = OperationTokenType.WORKFLOW_UPDATE;
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = null;
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

  public String getActivityId() {
    return activityId;
  }

  /**
   * Returns the activity run ID embedded in the token, or {@code null} if absent.
   *
   * <p>For activity-execution tokens, the run ID is only present after the start RPC completes.
   * Workflow-update tokens may also carry a run ID.
   */
  public String getRunId() {
    return runId;
  }
}
