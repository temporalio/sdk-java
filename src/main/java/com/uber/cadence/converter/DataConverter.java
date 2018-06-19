/*
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

package com.uber.cadence.converter;

import java.lang.reflect.Type;

/**
 * Used by the framework to serialize/deserialize method parameters that need to be sent over the
 * wire.
 *
 * @author fateev
 */
public interface DataConverter {

  /**
   * Implements conversion of a list of values.
   *
   * @param value Java value to convert to String.
   * @return converted value
   * @throws DataConverterException if conversion of the value passed as parameter failed for any
   *     reason.
   */
  byte[] toData(Object... value) throws DataConverterException;

  /**
   * Implements conversion of a single value.
   *
   * @param content Serialized value to convert to a Java object.
   * @param valueClass
   * @param valueType
   * @return converted Java object
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  <T> T fromData(byte[] content, Class<T> valueClass, Type valueType) throws DataConverterException;

  /**
   * Implements conversion of an array of values of different types. Useful for deserializing
   * arguments of function invocations.
   *
   * @param content serialized value to convert to Java objects.
   * @return array of converted Java objects
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  Object[] fromDataArray(byte[] content, Type... valueType) throws DataConverterException;
}
