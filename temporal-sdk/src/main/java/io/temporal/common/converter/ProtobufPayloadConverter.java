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

import com.google.protobuf.MessageLite;
import io.temporal.api.common.v1.Payload;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Optional;

public final class ProtobufPayloadConverter extends AbstractProtobufPayloadConverter
    implements PayloadConverter {

  public ProtobufPayloadConverter() {
    super();
  }

  public ProtobufPayloadConverter(boolean excludeProtobufMessageTypes) {
    super(excludeProtobufMessageTypes);
  }

  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_PROTOBUF_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    if (!(value instanceof MessageLite)) {
      return Optional.empty();
    }
    try {
      Payload.Builder builder =
          Payload.newBuilder()
              .putMetadata(
                  EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_PROTOBUF)
              .setData(((MessageLite) value).toByteString());
      super.addMessageType(builder, value);
      return Optional.of(builder.build());
    } catch (Exception e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    if (!MessageLite.class.isAssignableFrom(valueClass)) {
      throw new IllegalArgumentException("Not a protobuf. valueClass=" + valueClass.getName());
    }
    try {
      Method parseFrom = valueClass.getMethod("parseFrom", ByteBuffer.class);
      Object instance = parseFrom.invoke(null, content.getData().asReadOnlyByteBuffer());
      super.checkMessageType(content, instance);
      return (T) instance;
    } catch (Exception e) {
      throw new DataConverterException(e);
    }
  }
}
