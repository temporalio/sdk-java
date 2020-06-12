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
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.google.protobuf.ByteString;
import io.temporal.proto.common.Payload;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JacksonJsonPayloadConverter implements PayloadConverter {

  private static final Logger log = LoggerFactory.getLogger(JacksonJsonPayloadConverter.class);

  private static final String TYPE_FIELD_NAME = "type";
  private static final String JSON_CONVERTER_TYPE = "JSON";
  private final ObjectMapper mapper;

  public JacksonJsonPayloadConverter() {
    mapper = new ObjectMapper();
    mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    JavaTimeModule timeModule = new JavaTimeModule();
    DateTimeFormatter pattern = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    LocalDateTimeDeserializer localDateTimeDeserializer = new LocalDateTimeDeserializer(pattern);
    timeModule.addDeserializer(LocalDateTime.class, localDateTimeDeserializer);
    LocalDateTimeSerializer localDateTimeSerializer = new LocalDateTimeSerializer(pattern);
    timeModule.addSerializer(LocalDateTime.class, localDateTimeSerializer);
    mapper.registerModule(timeModule);

    SimpleModule module =
        new SimpleModule() {
          @Override
          public void setupModule(SetupContext context) {
            super.setupModule(context);
            // TODO:
            //            addSerializer(new DataConverterSerializer());
            //                        addDeserializer(
            //                            DataConverter.class,
            //                            new
            // DataConverterDeserializer(JacksonJsonDataConverter.getInstance()));
          }
        };
    mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    mapper.registerModule(module);
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

  private static class DataConverterSerializer extends StdSerializer<DataConverter> {

    protected DataConverterSerializer() {
      super(DataConverter.class);
    }

    @Override
    public void serialize(DataConverter value, JsonGenerator gen, SerializerProvider provider)
        throws IOException {
      gen.writeStartObject();
      gen.writeFieldName(TYPE_FIELD_NAME);
      gen.writeRawValue(JSON_CONVERTER_TYPE);
      gen.writeEndObject();
    }
  }

  private static class DataConverterDeserializer extends StdDeserializer<DataConverter> {

    private final DataConverter converter;

    protected DataConverterDeserializer(DataConverter converter) {
      super(DataConverter.class);
      this.converter = converter;
    }

    @Override
    public DataConverter deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException, JsonProcessingException {
      JsonNode tree = p.getCodec().readTree(p);
      JsonNode node = tree.get(TYPE_FIELD_NAME);
      if (node == null) {
        throw new IOException("Cannot deserialize DataConverter. Missing type field");
      }
      String value = node.textValue();
      if (!"JSON".equals(value)) {
        throw new IOException(
            "Cannot deserialize DataConverter. Expected type is JSON. " + "Found " + value);
      }
      return converter;
    }
  }

  //  private static class LocalDateTimeSerializer extends StdSerializer<LocalDateTime> {
  //
  //    protected LocalDateTimeSerializer() {
  //      super(LocalDateTime.class);
  //    }
  //
  //    @Override
  //    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider provider)
  //        throws IOException {
  //      System.out.println("DataConverterSerializer: " + value.getClass().getName());
  //      gen.writeStartObject();
  //      gen.writeFieldName(TYPE_FIELD_NAME);
  //      gen.writeRawValue(JSON_CONVERTER_TYPE);
  //      gen.writeEndObject();
  //    }
  //  }
  //
  //  private static class LocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {
  //
  //    private final LocalDateTime converter;
  //
  //    protected LocalDateTimeDeserializer(LocalDateTime converter) {
  //      super(DataConverter.class);
  //      this.converter = converter;
  //    }
  //
  //    @Override
  //    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt)
  //        throws IOException, JsonProcessingException {
  //      JsonNode tree = p.getCodec().readTree(p);
  //      JsonNode node = tree.get(TYPE_FIELD_NAME);
  //      if (node == null) {
  //        throw new IOException("Cannot deserialize DataConverter. Missing type field");
  //      }
  //      String value = node.textValue();
  //      if (!"JSON".equals(value)) {
  //        throw new IOException(
  //            "Cannot deserialize DataConverter. Expected type is JSON. " + "Found " + value);
  //      }
  //      return converter;
  //    }
  //  }
}
