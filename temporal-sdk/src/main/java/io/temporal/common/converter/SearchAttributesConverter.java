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

package io.temporal.common.converter;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SearchAttributesConverter {

  private static final Logger log = LoggerFactory.getLogger(SearchAttributesConverter.class);
  private static ObjectMapper mapper;

  static {
    mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.registerModule(new JavaTimeModule());
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
  }

  public static Optional<Payload> toData(Object obj) throws DataConverterException {
    try {
      byte[] serialized = mapper.writeValueAsBytes(obj);
      return Optional.of(
          Payload.newBuilder()
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_JSON)
              .putMetadata(
                  EncodingKeys.METADATA_TYPE_KEY,
                  ByteString.copyFromUtf8(
                      // Handle NPE
                      javaTypeToEncodedType(obj.getClass()).name()))
              .setData(ByteString.copyFrom(serialized))
              .build());
    } catch (JsonProcessingException e) {
      throw new DataConverterException(e);
    }
  }

  public static <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    ByteString data = content.getData();
    if (data.isEmpty()) {
      return null;
    }
    try {
      @SuppressWarnings("deprecation")
      JavaType reference = mapper.getTypeFactory().constructType(valueType, valueClass);
      return mapper.readValue(content.getData().toByteArray(), reference);
    } catch (IOException e) {
      throw new DataConverterException(e);
    }
  }

  public static SearchAttributes encode(Map<String, Object> searchAttributes) {
    return SearchAttributes.newBuilder()
        .putAllIndexedFields(objectToPayloadMap(searchAttributes))
        .build();
  }

  public static Map<String, Object> decode(SearchAttributes searchAttributes) {
    if (searchAttributes == null || searchAttributes.getIndexedFieldsCount() == 0) {
      return null;
    }
    return payloadToObjectMap(searchAttributes.getIndexedFieldsMap());
  }

  public static Map<String, Payload> objectToPayloadMap(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return null;
    }
    Map<String, Payload> mapOfByteBuffer = new HashMap<>();
    map.forEach((key, value) -> mapOfByteBuffer.put(key, toData(value).get()));
    return mapOfByteBuffer;
  }

  public static Map<String, Object> payloadToObjectMap(Map<String, Payload> serializedMap) {
    if (serializedMap == null || serializedMap.isEmpty()) {
      return null;
    }
    Map<String, Object> deserializedMap = new HashMap<>();
    for (Map.Entry<String, Payload> attribute : serializedMap.entrySet()) {
      String key = attribute.getKey();
      if (!parseDefaultSearchAttributes(key, attribute.getValue(), deserializedMap)) {
        String type =
            attribute
                .getValue()
                .getMetadataMap()
                .get(EncodingKeys.METADATA_TYPE_KEY)
                .toStringUtf8();
        Type javaType = encodedTypeToJavaType(type);
        if (javaType == null) {
          log.error("Error parsing Search Attribute: {} of type {}.", key, type);
        } else {
          deserializedMap.put(key, fromData(attribute.getValue(), javaType.getClass(), javaType));
        }
      }
    }
    return deserializedMap;
  }

  private static boolean parseDefaultSearchAttributes(
      String key, Payload value, Map<String, Object> deserializedMap) {
    DefaultSearchAttributes searchAttribute;
    try {
      searchAttribute = DefaultSearchAttributes.valueOf(key);
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
        deserializedMap.put(key, fromData(value, String.class, String.class));
        break;
      case CloseTime:
      case ExecutionTime:
      case StartTime:
        deserializedMap.put(key, fromData(value, LocalDateTime.class, LocalDateTime.class));
        break;
      case ExecutionDuration:
      case HistoryLength:
      case StateTransitionCount:
        deserializedMap.put(key, fromData(value, Integer.class, Integer.class));
        break;
    }
    return true;
  }

  public static Type encodedTypeToJavaType(String type) {
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

  public static SearchAttributeType javaTypeToEncodedType(Class type) {
    if (String.class.equals(type)) {
      return SearchAttributeType.String;
    } else if (Integer.class.equals(type)) {
      return SearchAttributeType.Int;
    } else if (Double.class.equals(type) || Float.class.equals(type)) {
      return SearchAttributeType.Double;
    } else if (Boolean.class.equals(type)) {
      return SearchAttributeType.Bool;
    } else if (LocalDateTime.class.equals(type)) {
      return SearchAttributeType.Datetime;
    }
    return SearchAttributeType.Unspecified;
  }

  public enum DefaultSearchAttributes {
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
}
