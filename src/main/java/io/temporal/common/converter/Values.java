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

import java.lang.reflect.Type;

/** Value that can be extracted to an appropriate type. */
public interface Values {

  int getSize();

  /** The same as {@link #get(int, Class)} with 0 index. */
  default <T> T get(Class<T> parameterType) throws DataConverterException {
    return get(0, parameterType);
  }

  /**
   * Get value of the specified type.
   *
   * @param index index of the value in the list of values.
   * @param parameterType class of the value to get
   * @param <T> type of the value to get
   * @return value or null
   * @throws DataConverterException if value cannot be extracted to the given type
   */
  <T> T get(int index, Class<T> parameterType) throws DataConverterException;

  /** The same as {@link #get(int, Class, Type)} with 0 index. */
  default <T> T get(Class<T> parameterType, Type genericParameterType)
      throws DataConverterException {
    return get(0, parameterType, genericParameterType);
  }

  /**
   * Get value of the specified generic type. For example if value is of type List<MyClass> use the
   * following expression (using {@link com.google.common.reflect.TypeToken}) to extract:
   *
   * <pre><code>
   * TypeToken&lt;List&lt;MyClass&gt;&gt; typeToken = new TypeToken&lt;List&lt;MyClass&gt;&gt;() {};
   * List&lt;MyClass&gt; result = value.get(List.class, typeToken.getType());
   *  </code></pre>
   *
   * @param index index of the value in the list of values.
   * @param parameterType class of the value to get
   * @param genericParameterType the type of the value to get
   * @param <T> type of the value to get
   * @return value or null
   * @throws DataConverterException if value cannot be extracted to the given type
   */
  <T> T get(int index, Class<T> parameterType, Type genericParameterType)
      throws DataConverterException;
}
