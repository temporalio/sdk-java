package io.temporal.internal.worker;

public final class WorkflowTaskPayloadStatsContext {

  private static final ThreadLocal<WorkflowTaskPayloadStats> CURRENT = new ThreadLocal<>();

  public static void set(WorkflowTaskPayloadStats stats) {
    CURRENT.set(stats);
  }

  public static WorkflowTaskPayloadStats get() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
