/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.common;

import com.google.common.base.CaseFormat;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

/**
 * Helper methods supporting transformation of History's "Proto Json" compatible format, which is
 * supported by {@link com.google.protobuf.util.JsonFormat} to the format of Temporal history
 * supported by tctl and back.
 *
 * @see <a
 *     href="https://github.com/temporalio/gogo-protobuf/commit/b38fb010909b8f81e2e600dc6f04925fc71d6a5e">
 *     Related commit to Go Proto module</>
 */
class HistoryJsonUtils {
  private static final JsonPath EVENT_TYPE_PATH = JsonPath.compile("$.events.*.eventType");
  private static final JsonPath TASK_QUEUE_KIND_PATH =
      JsonPath.compile("$.events.*.*.taskQueue.kind");
  private static final String EVENT_TYPE_PREFIX = "EVENT_TYPE_";
  private static final String TASK_QUEUE_KIND_PREFIX = "TASK_QUEUE_KIND_";

  public static String protoJsonToHistoryFormatJson(String protoJson) {
    DocumentContext parsed = JsonPath.parse(protoJson);
    parsed.map(
        EVENT_TYPE_PATH,
        (currentValue, configuration) ->
            enumProtoToHistory((String) currentValue, EVENT_TYPE_PREFIX));
    parsed.map(
        TASK_QUEUE_KIND_PATH,
        (currentValue, configuration) ->
            enumProtoToHistory((String) currentValue, TASK_QUEUE_KIND_PREFIX));
    return parsed.jsonString();
  }

  public static String historyFormatJsonToProtoJson(String historyFormatJson) {
    DocumentContext parsed = JsonPath.parse(historyFormatJson);
    parsed.map(
        EVENT_TYPE_PATH,
        (currentValue, configuration) ->
            enumHistoryToProto((String) currentValue, EVENT_TYPE_PREFIX));
    parsed.map(
        TASK_QUEUE_KIND_PATH,
        (currentValue, configuration) ->
            enumHistoryToProto((String) currentValue, TASK_QUEUE_KIND_PREFIX));
    return parsed.jsonString();
  }

  private static String enumProtoToHistory(String protoEnumValue, String prefix) {
    if (!protoEnumValue.startsWith(prefix)) {
      throw new IllegalArgumentException("protoEnumValue should start with " + prefix + " prefix");
    }
    protoEnumValue = protoEnumValue.substring(prefix.length());
    return screamingCaseEventTypeToCamelCase(protoEnumValue);
  }

  private static String enumHistoryToProto(String historyEnumValue, String prefix) {
    return prefix + camelCaseToScreamingCase(historyEnumValue);
  }

  // https://github.com/temporalio/gogo-protobuf/commit/b38fb010909b8f81e2e600dc6f04925fc71d6a5e
  private static String camelCaseToScreamingCase(String camel) {
    return CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.UPPER_UNDERSCORE).convert(camel);
  }

  // https://github.com/temporalio/gogo-protobuf/commit/b38fb010909b8f81e2e600dc6f04925fc71d6a5e
  private static String screamingCaseEventTypeToCamelCase(String screaming) {
    return CaseFormat.UPPER_UNDERSCORE.converterTo(CaseFormat.UPPER_CAMEL).convert(screaming);
  }
}
