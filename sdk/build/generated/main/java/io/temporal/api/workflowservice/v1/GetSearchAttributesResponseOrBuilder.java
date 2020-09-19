// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: temporal/api/workflowservice/v1/request_response.proto

package io.temporal.api.workflowservice.v1;

public interface GetSearchAttributesResponseOrBuilder extends
    // @@protoc_insertion_point(interface_extends:temporal.api.workflowservice.v1.GetSearchAttributesResponse)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>map&lt;string, .temporal.api.enums.v1.IndexedValueType&gt; keys = 1;</code>
   */
  int getKeysCount();
  /**
   * <code>map&lt;string, .temporal.api.enums.v1.IndexedValueType&gt; keys = 1;</code>
   */
  boolean containsKeys(
      java.lang.String key);
  /**
   * Use {@link #getKeysMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, io.temporal.api.enums.v1.IndexedValueType>
  getKeys();
  /**
   * <code>map&lt;string, .temporal.api.enums.v1.IndexedValueType&gt; keys = 1;</code>
   */
  java.util.Map<java.lang.String, io.temporal.api.enums.v1.IndexedValueType>
  getKeysMap();
  /**
   * <code>map&lt;string, .temporal.api.enums.v1.IndexedValueType&gt; keys = 1;</code>
   */
  io.temporal.api.enums.v1.IndexedValueType getKeysOrDefault(
      java.lang.String key,
      io.temporal.api.enums.v1.IndexedValueType defaultValue);
  /**
   * <code>map&lt;string, .temporal.api.enums.v1.IndexedValueType&gt; keys = 1;</code>
   */
  io.temporal.api.enums.v1.IndexedValueType getKeysOrThrow(
      java.lang.String key);
  /**
   * Use {@link #getKeysValueMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, java.lang.Integer>
  getKeysValue();
  /**
   * <code>map&lt;string, .temporal.api.enums.v1.IndexedValueType&gt; keys = 1;</code>
   */
  java.util.Map<java.lang.String, java.lang.Integer>
  getKeysValueMap();
  /**
   * <code>map&lt;string, .temporal.api.enums.v1.IndexedValueType&gt; keys = 1;</code>
   */

  int getKeysValueOrDefault(
      java.lang.String key,
      int defaultValue);
  /**
   * <code>map&lt;string, .temporal.api.enums.v1.IndexedValueType&gt; keys = 1;</code>
   */

  int getKeysValueOrThrow(
      java.lang.String key);
}
