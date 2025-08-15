package io.temporal.opentracing;

public enum SpanOperationType {
  START_WORKFLOW("StartWorkflow"),
  SIGNAL_WITH_START_WORKFLOW("SignalWithStartWorkflow"),
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
  RUN_CANCEL_NEXUS_OPERATION("RunCancelNexusOperationHandler"),
  RUN_FETCH_NEXUS_OPERATION_INFO("RunFetchNexusOperationInfoHandler"),
  RUN_FETCH_NEXUS_OPERATION_RESULT("RunFetchNexusOperationResultHandler"),
  CLIENT_START_NEXUS_OPERATION("ClientStartNexusOperation"),
  CLIENT_CANCEL_NEXUS_OPERATION("ClientCancelNexusOperation"),
  CLIENT_FETCH_NEXUS_OPERATION_INFO("ClientFetchNexusOperationInfo"),
  CLIENT_FETCH_NEXUS_OPERATION_RESULT("ClientFetchNexusOperationResult");

  private final String defaultPrefix;

  SpanOperationType(String defaultPrefix) {
    this.defaultPrefix = defaultPrefix;
  }

  public String getDefaultPrefix() {
    return defaultPrefix;
  }
}
