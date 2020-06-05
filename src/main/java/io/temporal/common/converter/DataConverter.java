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
import io.temporal.proto.common.Payloads;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Used by the framework to serialize/deserialize method parameters that need to be sent over the
 * wire.
 *
 * @author fateev
 */
public interface DataConverter {

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
  Optional<Payloads> toData(Object... values) throws DataConverterException;

  /**
   * Implements conversion of a single value.
   *
   * @param content Serialized value to convert to a Java object.
   * @param parameterType type of the parameter stored in the content
   * @param genericParameterType generic type of the parameter stored in the content
   * @return converted Java object
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  <T> T fromData(Optional<Payloads> content, Class<T> parameterType, Type genericParameterType)
      throws DataConverterException;

  /**
   * Implements conversion of an array of values of different types. Useful for deserializing
   * arguments of function invocations.
   *
   * @param content serialized value to convert to Java objects.
   * @param parameterTypes types of the parameters stored in the content
   * @param genericParameterTypes generic types of the parameters stored in the content
   * @return array of converted Java objects
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  public Object[] fromDataArray(
      Optional<Payloads> content, Class<?>[] parameterTypes, Type[] genericParameterTypes)
      throws DataConverterException;
}
