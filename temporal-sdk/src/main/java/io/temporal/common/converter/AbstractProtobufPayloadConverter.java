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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageOrBuilder;
import io.temporal.api.common.v1.Payload;
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

  protected void checkMessageType(Payload payload, Object instance) {
    if (!log.isWarnEnabled()) {
      return;
    }

    ByteString messageTypeBytes =
        payload.getMetadataMap().get(EncodingKeys.METADATA_MESSAGE_TYPE_KEY);
    if (messageTypeBytes != null) {
      String messageType = messageTypeBytes.toString(UTF_8);
      String instanceType = ((MessageOrBuilder) instance).getDescriptorForType().getFullName();

      if (!messageType.equals(instanceType)) {
        log.warn(
            "Encoded protobuf message type \""
                + messageType
                + "\" does not match value type \""
                + instanceType
                + '"');
      }
    }
  }
}
