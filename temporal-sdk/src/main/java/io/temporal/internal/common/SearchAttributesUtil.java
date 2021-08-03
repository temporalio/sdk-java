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

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.common.converter.DefaultDataConverter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchAttributesUtil {

  public static DefaultDataConverter converter = DefaultDataConverter.newDefaultInstance();
  private static final Logger log = LoggerFactory.getLogger(SearchAttributesUtil.class);

  public enum SearchAttribute {
    BatcherNamespace,
    BatcherUser,
    BinaryChecksums,
    CloseTime,
    ExecutionDuration,
    ExecutionStatus,
    ExecutionTime,
    HistoryLength,
    RunId,
    StartTime,
    StateTransitionCount,
    TaskQueue,
    TemporalChangeVersion,
    WorkflowId,
    WorkflowType
  }

  public enum SearchAttributeType {
    Unspecified,
    String,
    Keyword,
    Int,
    Integer,
    Double,
    Bool,
    Boolean,
    Datetime,
    LocalDateTime
  }

  public static Map<String, Object> deserializeToObjectMap(SearchAttributes serializedMap) {
    if (serializedMap == null) {
      return null;
    }

    Map<String, Object> deserializedMap = new HashMap<>();
    for (Entry<String, Payload> attribute : serializedMap.getIndexedFieldsMap().entrySet()) {
      String key = attribute.getKey();
      String value = converter.fromPayload(attribute.getValue(), String.class, String.class);
      if (!parseSearchAttributes(key, value, deserializedMap)) {
        String type = attribute.getValue().getMetadataMap().get("type").toStringUtf8();
        boolean customAttributeFound = parseCustomAttribute(key, type, value, deserializedMap);
        if (!customAttributeFound) {
          log.error("Error parsing Search Attribute: " + key + " of type " + type);
        }
      }
    }
    return deserializedMap;
  }

  private static boolean parseSearchAttributes(
      String key, String value, Map<String, Object> deserializedMap) {
    try {
      SearchAttribute searchAttribute = SearchAttribute.valueOf(key);
      switch (searchAttribute) {
        case BatcherNamespace:
        case BatcherUser:
        case BinaryChecksums:
        case ExecutionStatus:
        case RunId:
        case TaskQueue:
        case TemporalChangeVersion:
        case WorkflowId:
        case WorkflowType:
          deserializedMap.put(key, value);
          break;
        case CloseTime:
        case ExecutionTime:
        case StartTime:
          deserializedMap.put(key, LocalDateTime.parse(value));
          break;
        case ExecutionDuration:
        case HistoryLength:
        case StateTransitionCount:
          deserializedMap.put(key, Integer.parseInt(value));
          break;
      }
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static boolean parseCustomAttribute(
      String key, String type, String val, Map<String, Object> deserializedMap) {
    try {
      SearchAttributeType attributeType = SearchAttributeType.valueOf(type);
      switch (attributeType) {
        case Unspecified:
        case String:
        case Keyword:
          deserializedMap.put(key, val);
          break;
        case Int:
        case Integer:
          deserializedMap.put(key, Integer.parseInt(val));
          break;
        case Double:
          deserializedMap.put(key, Double.parseDouble(val));
          break;
        case Bool:
        case Boolean:
          deserializedMap.put(key, Boolean.parseBoolean(val));
          break;
        case Datetime:
        case LocalDateTime:
          deserializedMap.put(key, LocalDateTime.parse(val));
          break;
      }
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
