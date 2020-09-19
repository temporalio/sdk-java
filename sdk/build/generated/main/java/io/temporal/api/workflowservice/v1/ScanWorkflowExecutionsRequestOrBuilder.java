// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

public interface ScanWorkflowExecutionsRequestOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.workflowservice.v1.ScanWorkflowExecutionsRequest)
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
   * <code>int32 page_size = 2;</code>
   * @return The pageSize.
   */
  int getPageSize();

  /**
   * <code>bytes next_page_token = 3;</code>
   * @return The nextPageToken.
   */
  com.google.protobuf.ByteString getNextPageToken();

  /**
   * <code>string query = 4;</code>
   * @return The query.
   */
  java.lang.String getQuery();
  /**
   * <code>string query = 4;</code>
   * @return The bytes for query.
   */
  com.google.protobuf.ByteString
      getQueryBytes();
}
