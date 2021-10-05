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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.temporal.api.common.v1.Payload;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

public final class ProtobufJsonPayloadConverter implements PayloadConverter {

  private final JsonFormat.Printer printer;
  private final JsonFormat.Parser parser;

  public ProtobufJsonPayloadConverter() {
    printer = JsonFormat.printer();
    parser = JsonFormat.parser().ignoringUnknownFields();
  }

  public ProtobufJsonPayloadConverter(JsonFormat.Printer printer, JsonFormat.Parser parser) {
    this.printer = Objects.requireNonNull(printer);
    this.parser = Objects.requireNonNull(parser);
  }

  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_PROTOBUF_JSON_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    if (!(value instanceof MessageOrBuilder)) {
      return Optional.empty();
    }

    try {
      String data = printer.print((MessageOrBuilder) value);
      return Optional.of(
          Payload.newBuilder()
              .putMetadata(
                  EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_PROTOBUF_JSON)
              .setData(ByteString.copyFrom(data, UTF_8))
              .build());
    } catch (InvalidProtocolBufferException e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    ByteString data = content.getData();
    if (!MessageOrBuilder.class.isAssignableFrom(valueClass)) {
      throw new IllegalArgumentException("Not a protobuf. valueClass=" + valueClass.getName());
    }
    try {
      Method toBuilder = valueClass.getMethod("newBuilder");
      Message.Builder builder = (Message.Builder) toBuilder.invoke(null);
      parser.merge(data.toString(UTF_8), builder);

      return (T) builder.build();
    } catch (Exception e) {
      throw new DataConverterException(e);
    }
  }
}
