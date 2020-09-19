// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

public interface ListOpenWorkflowExecutionsResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>repeated .temporal.api.workflow.v1.WorkflowExecutionInfo executions = 1;</code>
   */
  java.util.List<io.temporal.api.workflow.v1.WorkflowExecutionInfo> 
      getExecutionsList();
  /**
   * <code>repeated .temporal.api.workflow.v1.WorkflowExecutionInfo executions = 1;</code>
   */
  io.temporal.api.workflow.v1.WorkflowExecutionInfo getExecutions(int index);
  /**
   * <code>repeated .temporal.api.workflow.v1.WorkflowExecutionInfo executions = 1;</code>
   */
  int getExecutionsCount();
  /**
   * <code>repeated .temporal.api.workflow.v1.WorkflowExecutionInfo executions = 1;</code>
   */
  java.util.List<? extends io.temporal.api.workflow.v1.WorkflowExecutionInfoOrBuilder> 
      getExecutionsOrBuilderList();
  /**
   * <code>repeated .temporal.api.workflow.v1.WorkflowExecutionInfo executions = 1;</code>
   */
  io.temporal.api.workflow.v1.WorkflowExecutionInfoOrBuilder getExecutionsOrBuilder(
      int index);

  /**
   * <code>bytes next_page_token = 2;</code>
   * @return The nextPageToken.
   */
  com.google.protobuf.ByteString getNextPageToken();
}
