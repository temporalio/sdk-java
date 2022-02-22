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
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import java.util.function.BiFunction;

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
  private static final Configuration JSON_PATH_CONFIGURATION =
      Configuration.builder().options(Option.SUPPRESS_EXCEPTIONS).build();

  private enum EnumValueConversionPolicy {
    EVENT_TYPE("EVENT_TYPE_", JsonPath.compile("$.events.*.eventType")),
    TASK_QUEUE_KIND("TASK_QUEUE_KIND_", JsonPath.compile("$.events.*.*.taskQueue.kind")),
    PARENT_CLOSE_POLICY("PARENT_CLOSE_POLICY_", JsonPath.compile("$.events.*.*.parentClosePolicy")),
    WORKFLOW_ID_REUSE_POLICY(
        "WORKFLOW_ID_REUSE_POLICY_", JsonPath.compile("$.events.*.*.workflowIdReusePolicy")),
    INITIATOR("CONTINUE_AS_NEW_INITIATOR_", JsonPath.compile("$.events.*.*.initiator")),
    RETRY_STATE(
        "RETRY_STATE_",
        // can be inside workflowExecutionFailedEventAttributes
        JsonPath.compile("$.events.*.*.retryState"),
        // or inside workflowExecutionFailedEventAttributes.childWorkflowExecutionFailureInfo
        JsonPath.compile("$.events.*.*.*.retryState"));

    private final String protobufEnumPrefix;
    private final JsonPath[] jsonPaths;

    EnumValueConversionPolicy(String protobufEnumPrefix, JsonPath... jsonPaths) {
      this.jsonPaths = jsonPaths;
      this.protobufEnumPrefix = protobufEnumPrefix;
    }
  }

  public static String protoJsonToHistoryFormatJson(String protoJson) {
    return convertEnumValues(protoJson, HistoryJsonUtils::enumProtoToHistory);
  }

  public static String historyFormatJsonToProtoJson(String historyFormatJson) {
    return convertEnumValues(historyFormatJson, HistoryJsonUtils::enumHistoryToProto);
  }

  private static String convertEnumValues(
      String json, BiFunction<String, String, String> convertEnumValue) {
    DocumentContext parsed = JsonPath.parse(json, JSON_PATH_CONFIGURATION);
    for (EnumValueConversionPolicy policy : EnumValueConversionPolicy.values()) {
      for (JsonPath jsonPath : policy.jsonPaths) {
        parsed.map(
            jsonPath,
            (currentValue, configuration) ->
                convertEnumValue.apply((String) currentValue, policy.protobufEnumPrefix));
      }
    }
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
