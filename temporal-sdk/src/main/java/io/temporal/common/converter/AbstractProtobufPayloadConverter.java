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
import com.google.protobuf.Descriptors;
import com.google.protobuf.MessageOrBuilder;
import io.temporal.api.common.v1.Payload;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractProtobufPayloadConverter {
  protected static final Logger log =
      LoggerFactory.getLogger(AbstractProtobufPayloadConverter.class);

  private final boolean excludeProtobufMessageTypes;

  protected AbstractProtobufPayloadConverter() {
    this.excludeProtobufMessageTypes = false;
  }

  protected AbstractProtobufPayloadConverter(boolean excludeProtobufMessageTypes) {
    this.excludeProtobufMessageTypes = excludeProtobufMessageTypes;
  }

  protected void addMessageType(Payload.Builder builder, Object value) {
    if (this.excludeProtobufMessageTypes) {
      return;
    }
    String messageTypeName = ((MessageOrBuilder) value).getDescriptorForType().getFullName();
    builder.putMetadata(
        EncodingKeys.METADATA_MESSAGE_TYPE_KEY, ByteString.copyFrom(messageTypeName, UTF_8));
  }

  protected void checkMessageType(Payload payload, Class valueClass) throws DataConverterException {
    ByteString messageTypeBytes =
        payload.getMetadataMap().get(EncodingKeys.METADATA_MESSAGE_TYPE_KEY);
    if (messageTypeBytes != null) {
      try {
        String messageType = messageTypeBytes.toString(UTF_8);
        Method getDescriptor = valueClass.getMethod("getDescriptor");
        String valueMessageType =
            ((Descriptors.Descriptor) getDescriptor.invoke(null)).getFullName();
        if (!messageType.equals(valueMessageType) && log.isWarnEnabled()) {
          log.warn(
              "Encoded protobuf message type \""
                  + messageType
                  + "\" does not match valueClass `"
                  + valueClass.getName()
                  + "`, which has message type \""
                  + valueMessageType
                  + '"');
        }
      } catch (Exception e) {
        throw new DataConverterException(e);
      }
    }
  }
}
