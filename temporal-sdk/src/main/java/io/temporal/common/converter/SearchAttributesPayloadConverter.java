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
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.Optional;

public final class SearchAttributesPayloadConverter {

  private static ObjectMapper mapper;
  public static final SearchAttributesPayloadConverter INSTANCE =
      new SearchAttributesPayloadConverter();

  private SearchAttributesPayloadConverter() {
    mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.registerModule(new JavaTimeModule());
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
  }

  public Optional<Payload> toData(Object obj) throws DataConverterException {
    try {
      byte[] serialized = mapper.writeValueAsBytes(obj);
      return Optional.of(
          Payload.newBuilder()
              .setData(ByteString.copyFrom(serialized))
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_JSON)
              .build());
    } catch (JsonProcessingException e) {
      throw new DataConverterException(e);
    }
  }

  public <T> Object fromData(Payload payload) throws DataConverterException {
    ByteString data = payload.getData();
    String type = payload.getMetadataMap().get(EncodingKeys.METADATA_TYPE_KEY).toStringUtf8();
    Type javaType = encodedTypeToJavaType(type);
    if (data.isEmpty() || javaType == null) {
      return payload;
    } else {
      try {
        // TODO
        @SuppressWarnings("deprecation")
        JavaType reference = mapper.getTypeFactory().constructType(javaType, javaType.getClass());
        return mapper.readValue(data.toByteArray(), reference);
      } catch (IOException e) {
        throw new DataConverterException(e);
      }
    }
  }

  private static Type encodedTypeToJavaType(String type) {
    SearchAttributesUtil.SearchAttributeType attributeType;
    try {
      attributeType = SearchAttributesUtil.SearchAttributeType.valueOf(type);
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
      default:
        return null;
    }
  }
}
