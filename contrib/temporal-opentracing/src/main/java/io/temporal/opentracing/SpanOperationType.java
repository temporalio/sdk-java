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
  START_STANDALONE_ACTIVITY("StartStandaloneActivity"),
  RUN_STANDALONE_ACTIVITY("RunStandaloneActivity"),
  GET_STANDALONE_ACTIVITY_RESULT("GetStandaloneActivityResult"),
  DESCRIBE_STANDALONE_ACTIVITY("DescribeStandaloneActivity"),
  CANCEL_STANDALONE_ACTIVITY("CancelStandaloneActivity"),
  TERMINATE_STANDALONE_ACTIVITY("TerminateStandaloneActivity"),
  LIST_STANDALONE_ACTIVITIES("ListStandaloneActivities"),
  COUNT_STANDALONE_ACTIVITIES("CountStandaloneActivities"),
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
