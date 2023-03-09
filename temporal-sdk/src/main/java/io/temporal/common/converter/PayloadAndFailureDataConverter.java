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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Defaults;
import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.DefaultFailureConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.payload.SerializationContext;
import java.lang.reflect.Type;
import java.util.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

class PayloadAndFailureDataConverter implements DataConverter {
  // TODO we should make these fields final and make this DataConverter immutable immutable
  //  For that we need to deprecate currently mutating methods like
  //  DefaultDataConverter#withPayloadConverterOverrides and
  // DefaultDataConverter#withFailureConverter
  volatile List<PayloadConverter> converters;
  volatile Map<String, PayloadConverter> convertersMap;
  volatile FailureConverter failureConverter;
  private final @Nullable SerializationContext serializationContext;

  public PayloadAndFailureDataConverter(@Nonnull List<PayloadConverter> converters) {
    this(
        Collections.unmodifiableList(converters),
        createConvertersMap(converters),
        new DefaultFailureConverter(),
        null);
  }

  PayloadAndFailureDataConverter(
      @Nonnull List<PayloadConverter> converters,
      @Nonnull Map<String, PayloadConverter> convertersMap,
      @Nonnull FailureConverter failureConverter,
      @Nullable SerializationContext serializationContext) {
    this.failureConverter = Preconditions.checkNotNull(failureConverter, "failureConverter");
    this.converters = Preconditions.checkNotNull(converters, "converters");
    this.convertersMap = Preconditions.checkNotNull(convertersMap, "converterMap");
    this.serializationContext = serializationContext;
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) throws DataConverterException {
    for (PayloadConverter converter : converters) {
      Optional<Payload> result =
          (serializationContext != null ? converter.withContext(serializationContext) : converter)
              .toData(value);
      if (result.isPresent()) {
        return result;
      }
    }
    throw new DataConverterException(
        "No PayloadConverter is registered with this DataConverter that accepts value:" + value);
  }

  @Override
  public <T> T fromPayload(Payload payload, Class<T> valueClass, Type valueType)
      throws DataConverterException {
    try {
      String encoding =
          payload.getMetadataOrThrow(EncodingKeys.METADATA_ENCODING_KEY).toString(UTF_8);
      PayloadConverter converter = convertersMap.get(encoding);
      if (converter == null) {
        throw new DataConverterException(
            "No PayloadConverter is registered for an encoding: " + encoding);
      }
      return (serializationContext != null
              ? converter.withContext(serializationContext)
              : converter)
          .fromData(payload, valueClass, valueType);
    } catch (DataConverterException e) {
      throw e;
    } catch (Exception e) {
      throw new DataConverterException(payload, valueClass, e);
    }
  }

  @Override
  public Optional<Payloads> toPayloads(Object... values) throws DataConverterException {
    if (values == null || values.length == 0) {
      return Optional.empty();
    }
    try {
      Payloads.Builder result = Payloads.newBuilder();
      for (Object value : values) {
        result.addPayloads(toPayload(value).get());
      }
      return Optional.of(result.build());
    } catch (DataConverterException e) {
      throw e;
    } catch (Throwable e) {
      throw new DataConverterException(e);
    }
  }

  @Override
  public <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> parameterType, Type genericParameterType)
      throws DataConverterException {
    if (!content.isPresent()) {
      return Defaults.defaultValue(parameterType);
    }
    int count = content.get().getPayloadsCount();
    // To make adding arguments a backwards compatible change
    if (index >= count) {
      return Defaults.defaultValue(parameterType);
    }
    return fromPayload(content.get().getPayloads(index), parameterType, genericParameterType);
  }

  @Override
  @Nonnull
  public TemporalFailure failureToException(@Nonnull Failure failure) {
    Preconditions.checkNotNull(failure, "failure");
    return (serializationContext != null
            ? failureConverter.withContext(serializationContext)
            : failureConverter)
        .failureToException(failure, this);
  }

  @Override
  @Nonnull
  public Failure exceptionToFailure(@Nonnull Throwable throwable) {
    Preconditions.checkNotNull(throwable, "throwable");
    return (serializationContext != null
            ? failureConverter.withContext(serializationContext)
            : failureConverter)
        .exceptionToFailure(throwable, this);
  }

  @Override
  public @Nonnull DataConverter withContext(@Nonnull SerializationContext context) {
    return new PayloadAndFailureDataConverter(converters, convertersMap, failureConverter, context);
  }

  static Map<String, PayloadConverter> createConvertersMap(List<PayloadConverter> converters) {
    Map<String, PayloadConverter> newConverterMap = new HashMap<>();
    for (PayloadConverter converter : converters) {
      newConverterMap.put(converter.getEncodingType(), converter);
    }
    return newConverterMap;
  }
}
