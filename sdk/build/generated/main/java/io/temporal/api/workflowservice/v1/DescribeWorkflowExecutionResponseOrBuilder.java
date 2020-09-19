// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

public interface DescribeWorkflowExecutionResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.workflowservice.v1.DescribeWorkflowExecutionResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.temporal.api.workflow.v1.WorkflowExecutionConfig execution_config = 1;</code>
   * @return Whether the executionConfig field is set.
   */
  boolean hasExecutionConfig();
  /**
   * <code>.temporal.api.workflow.v1.WorkflowExecutionConfig execution_config = 1;</code>
   * @return The executionConfig.
   */
  io.temporal.api.workflow.v1.WorkflowExecutionConfig getExecutionConfig();
  /**
   * <code>.temporal.api.workflow.v1.WorkflowExecutionConfig execution_config = 1;</code>
   */
  io.temporal.api.workflow.v1.WorkflowExecutionConfigOrBuilder getExecutionConfigOrBuilder();

  /**
   * <code>.temporal.api.workflow.v1.WorkflowExecutionInfo workflow_execution_info = 2;</code>
   * @return Whether the workflowExecutionInfo field is set.
   */
  boolean hasWorkflowExecutionInfo();
  /**
   * <code>.temporal.api.workflow.v1.WorkflowExecutionInfo workflow_execution_info = 2;</code>
   * @return The workflowExecutionInfo.
   */
  io.temporal.api.workflow.v1.WorkflowExecutionInfo getWorkflowExecutionInfo();
  /**
   * <code>.temporal.api.workflow.v1.WorkflowExecutionInfo workflow_execution_info = 2;</code>
   */
  io.temporal.api.workflow.v1.WorkflowExecutionInfoOrBuilder getWorkflowExecutionInfoOrBuilder();

  /**
   * <code>repeated .temporal.api.workflow.v1.PendingActivityInfo pending_activities = 3;</code>
   */
  java.util.List<io.temporal.api.workflow.v1.PendingActivityInfo> 
      getPendingActivitiesList();
  /**
   * <code>repeated .temporal.api.workflow.v1.PendingActivityInfo pending_activities = 3;</code>
   */
  io.temporal.api.workflow.v1.PendingActivityInfo getPendingActivities(int index);
  /**
   * <code>repeated .temporal.api.workflow.v1.PendingActivityInfo pending_activities = 3;</code>
   */
  int getPendingActivitiesCount();
  /**
   * <code>repeated .temporal.api.workflow.v1.PendingActivityInfo pending_activities = 3;</code>
   */
  java.util.List<? extends io.temporal.api.workflow.v1.PendingActivityInfoOrBuilder> 
      getPendingActivitiesOrBuilderList();
  /**
   * <code>repeated .temporal.api.workflow.v1.PendingActivityInfo pending_activities = 3;</code>
   */
  io.temporal.api.workflow.v1.PendingActivityInfoOrBuilder getPendingActivitiesOrBuilder(
      int index);

  /**
   * <code>repeated .temporal.api.workflow.v1.PendingChildExecutionInfo pending_children = 4;</code>
   */
  java.util.List<io.temporal.api.workflow.v1.PendingChildExecutionInfo> 
      getPendingChildrenList();
  /**
   * <code>repeated .temporal.api.workflow.v1.PendingChildExecutionInfo pending_children = 4;</code>
   */
  io.temporal.api.workflow.v1.PendingChildExecutionInfo getPendingChildren(int index);
  /**
   * <code>repeated .temporal.api.workflow.v1.PendingChildExecutionInfo pending_children = 4;</code>
   */
  int getPendingChildrenCount();
  /**
   * <code>repeated .temporal.api.workflow.v1.PendingChildExecutionInfo pending_children = 4;</code>
   */
  java.util.List<? extends io.temporal.api.workflow.v1.PendingChildExecutionInfoOrBuilder> 
      getPendingChildrenOrBuilderList();
  /**
   * <code>repeated .temporal.api.workflow.v1.PendingChildExecutionInfo pending_children = 4;</code>
   */
  io.temporal.api.workflow.v1.PendingChildExecutionInfoOrBuilder getPendingChildrenOrBuilder(
      int index);
}
