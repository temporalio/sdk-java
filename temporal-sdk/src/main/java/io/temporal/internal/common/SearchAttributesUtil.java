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

import java.lang.reflect.Type;
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
    Double,
    Bool,
    Datetime
  }

  public static Map<String, Object> deserializeToObjectMap(SearchAttributes serializedMap) {
    if (serializedMap == null) {
      return null;
    }

    Map<String, Object> deserializedMap = new HashMap<>();
    for (Entry<String, Payload> attribute : serializedMap.getIndexedFieldsMap().entrySet()) {
      String key = attribute.getKey();
      if (!parseSearchAttributes(key, attribute.getValue(), deserializedMap)) {
        String typeString = attribute.getValue().getMetadataMap().get("type").toStringUtf8();
        Type javaType = stringToJavaType(typeString);
        if (javaType == null) {
          log.error("Error parsing Search Attribute: " + key + " of type " + typeString);
        } else {
          deserializedMap.put(key, converter.fromPayload(attribute.getValue(), javaType.getClass(), javaType));
        }
      }
    }
    return deserializedMap;
  }

  private static boolean parseSearchAttributes(
      String key, Payload value, Map<String, Object> deserializedMap) {
    SearchAttribute searchAttribute;
    try {
      searchAttribute = SearchAttribute.valueOf(key);
    } catch (IllegalArgumentException e) {
      return false;
    }
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
          deserializedMap.put(key, converter.fromPayload(value, String.class, String.class));
          break;
        case CloseTime:
        case ExecutionTime:
        case StartTime:
          deserializedMap.put(key, converter.fromPayload(value, LocalDateTime.class, LocalDateTime.class));
          break;
        case ExecutionDuration:
        case HistoryLength:
        case StateTransitionCount:
          deserializedMap.put(key, converter.fromPayload(value, Integer.class, Integer.class));
          break;
      }
      return true;
  }

  private static Type stringToJavaType(String type) {
    SearchAttributeType attributeType;
    try {
      attributeType = SearchAttributeType.valueOf(type);
    } catch (IllegalArgumentException e) {
      return null;
    }
      switch (attributeType) {
        case String:
        case Keyword:
          return String.class;
        case Int:
          return Integer.class;
        case Double:
          return Double.class;
        case Bool:
          return Boolean.class;
        case Datetime:
          return LocalDateTime.class;
  }
    return null;
  }
}
