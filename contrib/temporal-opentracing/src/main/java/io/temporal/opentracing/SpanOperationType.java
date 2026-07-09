package io.temporal.opentracing;

public enum SpanOperationType {
  START_WORKFLOW("StartWorkflow"),
  SIGNAL_WITH_START_WORKFLOW("SignalWithStartWorkflow"),
  UPDATE_WITH_START_WORKFLOW("UpdateWithStartWorkflow"),
  RUN_WORKFLOW("RunWorkflow"),
  START_CHILD_WORKFLOW("StartChildWorkflow"),
  START_CONTINUE_AS_NEW_WORKFLOW("StartContinueAsNewWorkflow"),
  START_ACTIVITY("StartActivity"),
  RUN_ACTIVITY("RunActivity"),
  SIGNAL_EXTERNAL_WORKFLOW("SignalExternalWorkflow"),
  QUERY_WORKFLOW("QueryWorkflow"),
  SIGNAL_WORKFLOW("SignalWorkflow"),
  UPDATE_WORKFLOW("UpdateWorkflow"),
  HANDLE_QUERY("HandleQuery"),
  HANDLE_SIGNAL("HandleSignal"),
  HANDLE_UPDATE("HandleUpdate"),
  START_NEXUS_OPERATION("StartNexusOperation"),
  RUN_START_NEXUS_OPERATION("RunStartNexusOperationHandler"),
  RUN_CANCEL_NEXUS_OPERATION("RunCancelNexusOperationHandler");

  private final String defaultPrefix;

  SpanOperationType(String defaultPrefix) {
    this.defaultPrefix = defaultPrefix;
  }

  public String getDefaultPrefix() {
    return defaultPrefix;
  }
}
