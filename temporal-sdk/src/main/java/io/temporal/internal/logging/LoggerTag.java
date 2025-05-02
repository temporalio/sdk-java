package io.temporal.internal.logging;

public final class LoggerTag {
  private LoggerTag() {}

  public static final String ACTIVITY_ID = "ActivityId";
  public static final String ACTIVITY_TYPE = "ActivityType";
  public static final String NAMESPACE = "Namespace";
  public static final String EVENT_ID = "EventId";
  public static final String EVENT_TYPE = "EventType";
  public static final String RUN_ID = "RunId";
  public static final String TASK_QUEUE = "TaskQueue";
  public static final String TIMER_ID = "TimerId";
  public static final String WORKFLOW_ID = "WorkflowId";
  public static final String WORKFLOW_TYPE = "WorkflowType";
  public static final String WORKER_ID = "WorkerId";
  public static final String WORKER_TYPE = "WorkerType";
  public static final String SIDE_EFFECT_ID = "SideEffectId";
  public static final String CHILD_WORKFLOW_ID = "ChildWorkflowId";
  public static final String ATTEMPT = "Attempt";
  public static final String UPDATE_ID = "UpdateId";
  public static final String UPDATE_NAME = "UpdateName";
  public static final String NEXUS_SERVICE = "NexusService";
  public static final String NEXUS_OPERATION = "NexusOperation";
}
