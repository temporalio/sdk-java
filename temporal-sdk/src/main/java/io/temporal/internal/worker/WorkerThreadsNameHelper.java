package io.temporal.internal.worker;

class WorkerThreadsNameHelper {
  private static final String WORKFLOW_POLL_THREAD_NAME_PREFIX = "Workflow Poller taskQueue=";
  private static final String LOCAL_ACTIVITY_POLL_THREAD_NAME_PREFIX =
      "Local Activity Poller taskQueue=";
  private static final String ACTIVITY_POLL_THREAD_NAME_PREFIX = "Activity Poller taskQueue=";
  private static final String NEXUS_POLL_THREAD_NAME_PREFIX = "Nexus Poller taskQueue=";
  public static final String SHUTDOWN_MANAGER_THREAD_NAME_PREFIX = "TemporalShutdownManager";
  public static final String ACTIVITY_HEARTBEAT_THREAD_NAME_PREFIX = "TemporalActivityHeartbeat-";

  public static final String LOCAL_ACTIVITY_SCHEDULER_THREAD_NAME_PREFIX =
      "LocalActivityScheduler-";

  public static String getWorkflowPollerThreadPrefix(String namespace, String taskQueue) {
    return WORKFLOW_POLL_THREAD_NAME_PREFIX
        + "\""
        + taskQueue
        + "\", namespace=\""
        + namespace
        + "\"";
  }

  public static String getLocalActivityPollerThreadPrefix(String namespace, String taskQueue) {
    return LOCAL_ACTIVITY_POLL_THREAD_NAME_PREFIX
        + "\""
        + taskQueue
        + "\", namespace=\""
        + namespace
        + "\"";
  }

  public static String getActivityPollerThreadPrefix(String namespace, String taskQueue) {
    return ACTIVITY_POLL_THREAD_NAME_PREFIX
        + "\""
        + taskQueue
        + "\", namespace=\""
        + namespace
        + "\"";
  }

  public static String getActivityHeartbeatThreadPrefix(String namespace, String taskQueue) {
    return ACTIVITY_HEARTBEAT_THREAD_NAME_PREFIX + namespace + "-" + taskQueue;
  }

  public static String getLocalActivitySchedulerThreadPrefix(String namespace, String taskQueue) {
    return LOCAL_ACTIVITY_SCHEDULER_THREAD_NAME_PREFIX + namespace + "-" + taskQueue;
  }

  public static String getNexusPollerThreadPrefix(String namespace, String taskQueue) {
    return NEXUS_POLL_THREAD_NAME_PREFIX + "\"" + taskQueue + "\", namespace=\"" + namespace + "\"";
  }
}
