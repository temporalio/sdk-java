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

  @JsonCreator
  public OperationToken(
      @JsonProperty("t") Integer type,
      @JsonProperty("ns") String namespace,
      @JsonProperty("wid") String workflowId,
      @JsonProperty("aid") String activityId,
      @JsonProperty("rid") String runId,
      @JsonProperty("v") Integer version) {
    this.type = OperationTokenType.fromValue(type);
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = activityId;
    this.runId = runId;
    this.version = version;
  }

  public OperationToken(OperationTokenType type, String namespace, String workflowId) {
    this.type = type;
    this.namespace = namespace;
    this.workflowId = workflowId;
    this.activityId = null;
    this.runId = null;
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

  /**
   * Returns the activity run ID embedded in the token, or {@code null} if absent.
   *
   * <p>Run ID is only present on activity-execution tokens that were generated AFTER the start
   * activity RPC completed (so the run ID was known). Tokens written into the Nexus operation-token
   * callback header are generated before that point and therefore do not carry a run ID.
   */
  public String getRunId() {
    return runId;
  }
}
