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

import io.temporal.api.common.v1.Payloads;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.Optional;

public final class EncodedValues implements Values {
  private Optional<Payloads> payloads;
  private DataConverter converter;
  private final Object[] values;

  public EncodedValues(Optional<Payloads> payloads, DataConverter converter) {
    this.payloads = Objects.requireNonNull(payloads);
    this.converter = converter;
    this.values = null;
  }

  public EncodedValues(Object... values) {
    this.values = values;
    this.payloads = null;
  }

  public Optional<Payloads> toPayloads() {
    if (payloads == null) {
      if (values == null || values.length == 0) {
        payloads = Optional.empty();
      } else if (converter == null) {
        throw new IllegalStateException("converter not set");
      } else {
        payloads = converter.toPayloads(values);
      }
    }
    return payloads;
  }

  public void setDataConverter(DataConverter converter) {
    this.converter = Objects.requireNonNull(converter);
  }

  @Override
  public int getSize() {
    if (values != null) {
      return values.length;
    } else {
      if (payloads.isPresent()) {
        return payloads.get().getPayloadsCount();
      } else {
        return 0;
      }
    }
  }

  @Override
  public <T> T get(int index, Class<T> parameterType) throws DataConverterException {
    return get(index, parameterType, parameterType);
  }

  @Override
  public <T> T get(int index, Class<T> parameterType, Type genericParameterType)
      throws DataConverterException {
    if (values != null) {
      @SuppressWarnings("unchecked")
      T result = (T) values[index];
      return result;
    } else {
      if (converter == null) {
        throw new IllegalStateException("converter not set");
      }
      return converter.fromPayloads(index, payloads, parameterType, genericParameterType);
    }
  }
}
