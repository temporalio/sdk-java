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

  static DataConverter getDefaultInstance() {
    return DefaultDataConverter.getDefaultInstance();
  }

  <T> Optional<Payload> toPayload(T value);

  <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType);

  /**
   * Implements conversion of a list of values.
   *
   * @param values Java values to convert to String.
   * @return converted value
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
   * @param parameterType type of the parameter stored in the content
   * @param genericParameterType generic type of the parameter stored in the content
   * @return converted Java object
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> parameterType, Type genericParameterType)
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
