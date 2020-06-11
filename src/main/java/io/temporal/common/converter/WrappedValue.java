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

public final class WrappedValue implements Value {
  private final Object value;

  public WrappedValue(Object value) {
    this.value = value;
  }

  @Override
  public <T> T get(Class<T> parameterType) throws DataConverterException {
    return (T) value;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> T get(Class<T> parameterType, Type genericParameterType)
      throws DataConverterException {
    return (T) value;
  }
}
