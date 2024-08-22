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

package io.temporal.internal.nexus;

import com.google.protobuf.ByteString;
import io.nexusrpc.Serializer;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * PayloadSerializer is a serializer that converts objects to and from {@link
 * io.nexusrpc.Serializer.Content} objects by using the {@link DataConverter} to convert objects to
 * and from {@link Payload} objects.
 */
class PayloadSerializer implements Serializer {
  DataConverter dataConverter;

  PayloadSerializer(DataConverter dataConverter) {
    this.dataConverter = dataConverter;
  }

  @Override
  public Content serialize(@Nullable Object o) {
    Optional<Payload> payload = dataConverter.toPayload(o);
    Content.Builder content = Content.newBuilder();
    content.setData(payload.get().getData().toByteArray());
    payload.get().getMetadataMap().forEach((k, v) -> content.putHeader(k, v.toStringUtf8()));
    return content.build();
  }

  @Override
  public @Nullable Object deserialize(Content content, Type type) {
    Payload.Builder payload = Payload.newBuilder().setData(ByteString.copyFrom(content.getData()));
    content.getHeaders().forEach((k, v) -> payload.putMetadata(k, ByteString.copyFromUtf8(v)));
    return dataConverter.fromPayload(payload.build(), type.getClass(), type);
  }
}
