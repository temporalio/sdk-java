// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

public interface ListClosedWorkflowExecutionsRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string namespace = 1;</code>
   * @return The namespace.
   */
  java.lang.String getNamespace();
  /**
   * <code>string namespace = 1;</code>
   * @return The bytes for namespace.
   */
  com.google.protobuf.ByteString
      getNamespaceBytes();

  /**
   * <code>int32 maximum_page_size = 2;</code>
   * @return The maximumPageSize.
   */
  int getMaximumPageSize();

  /**
   * <code>bytes next_page_token = 3;</code>
   * @return The nextPageToken.
   */
  com.google.protobuf.ByteString getNextPageToken();

  /**
   * <code>.temporal.api.filter.v1.StartTimeFilter start_time_filter = 4;</code>
   * @return Whether the startTimeFilter field is set.
   */
  boolean hasStartTimeFilter();
  /**
   * <code>.temporal.api.filter.v1.StartTimeFilter start_time_filter = 4;</code>
   * @return The startTimeFilter.
   */
  io.temporal.api.filter.v1.StartTimeFilter getStartTimeFilter();
  /**
   * <code>.temporal.api.filter.v1.StartTimeFilter start_time_filter = 4;</code>
   */
  io.temporal.api.filter.v1.StartTimeFilterOrBuilder getStartTimeFilterOrBuilder();

  /**
   * <code>.temporal.api.filter.v1.WorkflowExecutionFilter execution_filter = 5;</code>
   * @return Whether the executionFilter field is set.
   */
  boolean hasExecutionFilter();
  /**
   * <code>.temporal.api.filter.v1.WorkflowExecutionFilter execution_filter = 5;</code>
   * @return The executionFilter.
   */
  io.temporal.api.filter.v1.WorkflowExecutionFilter getExecutionFilter();
  /**
   * <code>.temporal.api.filter.v1.WorkflowExecutionFilter execution_filter = 5;</code>
   */
  io.temporal.api.filter.v1.WorkflowExecutionFilterOrBuilder getExecutionFilterOrBuilder();

  /**
   * <code>.temporal.api.filter.v1.WorkflowTypeFilter type_filter = 6;</code>
   * @return Whether the typeFilter field is set.
   */
  boolean hasTypeFilter();
  /**
   * <code>.temporal.api.filter.v1.WorkflowTypeFilter type_filter = 6;</code>
   * @return The typeFilter.
   */
  io.temporal.api.filter.v1.WorkflowTypeFilter getTypeFilter();
  /**
   * <code>.temporal.api.filter.v1.WorkflowTypeFilter type_filter = 6;</code>
   */
  io.temporal.api.filter.v1.WorkflowTypeFilterOrBuilder getTypeFilterOrBuilder();

  /**
   * <code>.temporal.api.filter.v1.StatusFilter status_filter = 7;</code>
   * @return Whether the statusFilter field is set.
   */
  boolean hasStatusFilter();
  /**
   * <code>.temporal.api.filter.v1.StatusFilter status_filter = 7;</code>
   * @return The statusFilter.
   */
  io.temporal.api.filter.v1.StatusFilter getStatusFilter();
  /**
   * <code>.temporal.api.filter.v1.StatusFilter status_filter = 7;</code>
   */
  io.temporal.api.filter.v1.StatusFilterOrBuilder getStatusFilterOrBuilder();

  public io.temporal.api.workflowservice.v1.ListClosedWorkflowExecutionsRequest.FiltersCase getFiltersCase();
}
