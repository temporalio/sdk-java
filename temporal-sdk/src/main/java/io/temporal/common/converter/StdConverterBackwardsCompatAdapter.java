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

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Temporal SDK should use Standard data converter for fields needed for internal essential
 * functionality. See comments on {@link DefaultDataConverter#STANDARD_INSTANCE}
 *
 * <p>Unfortunately, bad code hygiene and overload of the word "default" with several meanings in
 * DefaultDataConverter led to a situation where a user-defined converter may be used to
 * serialize these internal fields instead. This problem is solved now and all SDK internal fields
 * are serialized using Standard data converter. But users may already have workflows created by
 * older SDKs and overridden "default" (in meaning "global") converter containing payloads in
 * internal fields that must be deserialized using the user-defined converter.
 *
 * <p>This class provides a compatibility layer for deserialization of such internal fields by first
 * using {@link DefaultDataConverter#STANDARD_INSTANCE} and falling back to {@link
 * GlobalDataConverter#get()} in case of an exception.
 */
public class StdConverterBackwardsCompatAdapter {
  public static <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType) {
    try {
      return DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, valueClass, valueType);
    } catch (DataConverterException e) {
      try {
        return GlobalDataConverter.get().fromPayload(payload, valueClass, valueType);
      } catch (DataConverterException legacyEx) {
        throw e;
      }
    }
  }

  public static <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException {
    try {
      return DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
          index, content, valueType, valueGenericType);
    } catch (DataConverterException e) {
      try {
        return GlobalDataConverter.get().fromPayloads(index, content, valueType, valueGenericType);
      } catch (DataConverterException legacyEx) {
        throw e;
      }
    }
  }
}
