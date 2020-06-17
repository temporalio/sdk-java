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

import io.temporal.proto.common.Payload;
import java.lang.reflect.Type;
import java.util.Optional;

/** Encodes and decodes null values. */
public final class NullPayloadConverter implements PayloadConverter {
  @Override
  public String getEncodingType() {
    return EncodingKeys.METADATA_ENCODING_NULL_NAME;
  }

  @Override
  public Optional<Payload> toData(Object value) throws DataConverterException {
    if (value == null) {
      return Optional.of(
          Payload.newBuilder()
              .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, EncodingKeys.METADATA_ENCODING_NULL)
              .build());
    }
    return Optional.empty();
  }

  @Override
  public <T> T fromData(Payload content, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    return null;
  }
}
