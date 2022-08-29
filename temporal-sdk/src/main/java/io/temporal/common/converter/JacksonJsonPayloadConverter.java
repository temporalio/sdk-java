/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.common.converter;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Optional;

public class JacksonJsonPayloadConverter implements PayloadConverter {

  private final ObjectMapper mapper;

  /**
   * Can be used as a starting point for custom user configurations of ObjectMapper.
   *
   * @return a default configuration of {@link ObjectMapper} used by {@link
   *     JacksonJsonPayloadConverter}.
   */
  public static ObjectMapper newDefaultObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    // preserve the original value of timezone coming from the server in Payload
    // without adjusting to the host timezone
    // may be important if the replay is happening on a host in another timezone
    mapper.configure(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE, false);
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(new Jdk8Module());
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    return mapper;
  }

  public JacksonJsonPayloadConverter() {
    this(newDefaultObjectMapper());
  }

  public JacksonJsonPayloadConverter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_JSON_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    try {
      byte[] serialized = mapper.writeValueAsBytes(value);
      return Optional.of(
          Payload.newBuilder()
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_JSON)
              .setData(ByteString.copyFrom(serialized))
              .build());

    } catch (JsonProcessingException e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
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
}
