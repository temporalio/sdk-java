package io.temporal.serviceclient;

import io.grpc.CallOptions;
import io.grpc.MethodDescriptor;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;

class LongPollUtil {

  static <ReqT, RespT> boolean isLongPoll(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions) {
    if (method == WorkflowServiceGrpc.getPollWorkflowTaskQueueMethod()
        || method == WorkflowServiceGrpc.getPollActivityTaskQueueMethod()
        || method == WorkflowServiceGrpc.getPollNexusTaskQueueMethod()
        || method == WorkflowServiceGrpc.getUpdateWorkflowExecutionMethod()
        || method == WorkflowServiceGrpc.getExecuteMultiOperationMethod()
        || method == WorkflowServiceGrpc.getPollWorkflowExecutionUpdateMethod()) {
      return true;
    }
    if (method == WorkflowServiceGrpc.getGetWorkflowExecutionHistoryMethod()) {
      Boolean longPoll = callOptions.getOption(MetricsTag.HISTORY_LONG_POLL_CALL_OPTIONS_KEY);
      return Boolean.TRUE.equals(longPoll);
    }
    return false;
  }

  private LongPollUtil() {}
}
