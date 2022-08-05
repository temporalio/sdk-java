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

import com.google.common.base.Defaults;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Optional;

/**
 * Used by the framework to serialize/deserialize method parameters that need to be sent over the
 * wire.
 *
 * @author fateev
 */
public interface DataConverter {

  /**
   * @deprecated use {@link GlobalDataConverter#get()}
   */
  @Deprecated
  static DataConverter getDefaultInstance() {
    return GlobalDataConverter.get();
  }

  /**
   * @param value value to convert
   * @return a {@link Payload} which is a protobuf message containing byte-array serialized
   *     representation of {@code value}. Optional here is for legacy and backward compatibility
   *     reasons. This Optional is expected to always be filled.
   * @throws DataConverterException if conversion fails
   */
  <T> Optional<Payload> toPayload(T value) throws DataConverterException;

  <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType)
      throws DataConverterException;

  /**
   * Implements conversion of a list of values.
   *
   * @param values Java values to convert to String.
   * @return converted value. Return empty Optional if values are empty.
   * @throws DataConverterException if conversion of the value passed as parameter failed for any
   *     reason.
   */
  Optional<Payloads> toPayloads(Object... values) throws DataConverterException;

  /**
   * Implements conversion of an array of values of different types. Useful for deserializing
   * arguments of function invocations.
   *
   * @param index index of the value in the payloads
   * @param content serialized value to convert to Java objects.
   * @param valueType type of the value stored in the content
   * @param valueGenericType generic type of the value stored in the content
   * @return converted Java object
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException;

  static Object[] arrayFromPayloads(
      DataConverter converter,
      Optional<Payloads> content,
      Class<?>[] parameterTypes,
      Type[] genericParameterTypes)
      throws DataConverterException {
    if (parameterTypes != null
        && (genericParameterTypes == null
            || parameterTypes.length != genericParameterTypes.length)) {
      throw new IllegalArgumentException(
          "parameterTypes don't match length of valueTypes: "
              + Arrays.toString(parameterTypes)
              + "<>"
              + Arrays.toString(genericParameterTypes));
    }

    int length = parameterTypes.length;
    Object[] result = new Object[length];
    if (!content.isPresent()) {
      // Return defaults for all the parameters
      for (int i = 0; i < parameterTypes.length; i++) {
        result[i] = Defaults.defaultValue((Class<?>) genericParameterTypes[i]);
      }
      return result;
    }
    Payloads payloads = content.get();
    int count = payloads.getPayloadsCount();
    for (int i = 0; i < parameterTypes.length; i++) {
      Class<?> pt = parameterTypes[i];
      Type gt = genericParameterTypes[i];
      if (i >= count) {
        result[i] = Defaults.defaultValue((Class<?>) gt);
      } else {
        result[i] = converter.fromPayload(payloads.getPayloads(i), pt, gt);
      }
    }
    return result;
  }
}
