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
import io.temporal.failure.FailureConverter;
import io.temporal.failure.TemporalFailure;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

/**
 * A {@link DataConverter} that delegates payload conversion to type specific {@link
 * PayloadConverter} instances, and delegates failure conversions to a {@link FailureConverter}.
 *
 * @author fateev
 */
public class DefaultDataConverter implements DataConverter {

  // Order is important as the first converter that can convert the payload is used. Needs to match
  // the other SDKs. Go SDK:
  // https://github.com/temporalio/sdk-go/blob/5e5645f0c550dcf717c095ae32c76a7087d2e985/converter/default_data_converter.go#L28
  public static final PayloadConverter[] STANDARD_PAYLOAD_CONVERTERS = {
    new NullPayloadConverter(),
    new ByteArrayPayloadConverter(),
    new ProtobufJsonPayloadConverter(),
    new ProtobufPayloadConverter(),
    new JacksonJsonPayloadConverter()
  };

  /**
   * Default data converter that is used for all objects if not overridden by {@link
   * io.temporal.client.WorkflowClientOptions.Builder#setDataConverter(DataConverter)} or {@link
   * GlobalDataConverter#register(DataConverter)} (less preferred).
   *
   * <p>This data converter is also always used (regardless of whether or not users have supplied
   * their own converter) to perform serialization of values essential for functionality of Temporal
   * SDK, Server, tctl or WebUI:
   *
   * <ul>
   *   <li>Local Activity, Version, MutableSideEffect Markers metadata like id, time, name
   *   <li>Search attribute values
   *   <li>Stacktrace query return value
   * </ul>
   */
  public static final DataConverter STANDARD_INSTANCE = newDefaultInstance();

  private final Map<String, PayloadConverter> converterMap = new ConcurrentHashMap<>();

  private final List<PayloadConverter> converters = new ArrayList<>();

  private FailureConverter failureConverter;

  /**
   * @deprecated use {@link GlobalDataConverter#register(DataConverter)}
   */
  public static void setDefaultDataConverter(DataConverter converter) {
    GlobalDataConverter.register(converter);
  }

  /**
   * Creates a new instance of {@code DefaultDataConverter} populated with the default list of
   * payload converters and a default failure converter.
   */
  public static DefaultDataConverter newDefaultInstance() {
    return new DefaultDataConverter(STANDARD_PAYLOAD_CONVERTERS);
  }

  /**
   * Creates instance from ordered array of converters and a default failure converter. When
   * converting an object to payload the array of converters is iterated from the beginning until
   * one of the converters successfully converts the value.
   */
  public DefaultDataConverter(PayloadConverter... converters) {
    this.failureConverter = new DefaultFailureConverter();
    Collections.addAll(this.converters, converters);
    updateConverterMap();
  }

  /**
   * Modifies this {@code DefaultDataConverter} by overriding some of its {@link PayloadConverter}s.
   * Every payload converter from {@code overrideConverters} either replaces existing payload
   * converter with the same encoding type, or is added to the end of payload converters list.
   */
  public DefaultDataConverter withPayloadConverterOverrides(
      PayloadConverter... overrideConverters) {
    for (PayloadConverter overrideConverter : overrideConverters) {
      PayloadConverter existingConverter = converterMap.get(overrideConverter.getEncodingType());
      if (existingConverter != null) {
        int existingConverterIndex = converters.indexOf(existingConverter);
        converters.set(existingConverterIndex, overrideConverter);
      } else {
        converters.add(overrideConverter);
      }
    }

    updateConverterMap();

    return this;
  }

  /**
   * Modifies this {@code DefaultDataConverter} by overriding its {@link FailureConverter}.
   *
   * <p>WARNING: Most users should _never_ need to override the default failure converter. To
   * encrypt the content of failures, see {@link CodecDataConverter} instead.
   *
   * @param failureConverter The failure converter to use
   * @throws NullPointerException if failureConverter is null
   */
  @Nonnull
  public DefaultDataConverter withFailureConverter(@Nonnull FailureConverter failureConverter) {
    Preconditions.checkNotNull(failureConverter, "failureConverter");
    this.failureConverter = failureConverter;
    return this;
  }

  @Override
  public <T> Optional<Payload> toPayload(T value) throws DataConverterException {
    for (PayloadConverter converter : converters) {
      Optional<Payload> result = converter.toData(value);
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
      PayloadConverter converter = converterMap.get(encoding);
      if (converter == null) {
        throw new DataConverterException(
            "No PayloadConverter is registered for an encoding: " + encoding);
      }
      return converter.fromData(payload, valueClass, valueType);
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
    return failureConverter.failureToException(failure, this);
  }

  @Override
  @Nonnull
  public Failure exceptionToFailure(@Nonnull Throwable throwable) {
    Preconditions.checkNotNull(throwable, "throwable");
    return failureConverter.exceptionToFailure(throwable, this);
  }

  private void updateConverterMap() {
    converterMap.clear();
    for (PayloadConverter converter : converters) {
      converterMap.put(converter.getEncodingType(), converter);
    }
  }
}
