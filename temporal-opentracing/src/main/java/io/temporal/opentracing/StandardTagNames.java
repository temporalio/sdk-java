package io.temporal.opentracing;

public class StandardTagNames {
  public static final String WORKFLOW_ID = "workflowId";
  public static final String RUN_ID = "runId";
  public static final String PARENT_WORKFLOW_ID = "parentWorkflowId";
  public static final String PARENT_RUN_ID = "parentRunId";

  /**
   * @deprecated use {@link io.opentracing.tag.Tags#ERROR}
   */
  @Deprecated public static final String FAILED = "failed";

  public static final String EVICTED = "evicted";
}
