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

import com.google.common.base.Preconditions;
import java.util.*;
import javax.annotation.Nonnull;

/**
 * A {@link DataConverter} that delegates payload conversion to type specific {@link
 * PayloadConverter} instances, and delegates failure conversions to a {@link FailureConverter}.
 *
 * @author fateev
 */
public class DefaultDataConverter extends PayloadAndFailureDataConverter {

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
    super(Arrays.asList(converters));
  }

  /**
   * Modifies this {@code DefaultDataConverter} by overriding some of its {@link PayloadConverter}s.
   * Every payload converter from {@code overrideConverters} either replaces existing payload
   * converter with the same encoding type, or is added to the end of payload converters list.
   */
  public DefaultDataConverter withPayloadConverterOverrides(
      PayloadConverter... overrideConverters) {
    List<PayloadConverter> newConverters = new ArrayList<>(converters);
    for (PayloadConverter overrideConverter : overrideConverters) {
      PayloadConverter existingConverter =
          this.convertersMap.get(overrideConverter.getEncodingType());
      if (existingConverter != null) {
        int existingConverterIndex = newConverters.indexOf(existingConverter);
        newConverters.set(existingConverterIndex, overrideConverter);
      } else {
        newConverters.add(overrideConverter);
      }
    }

    this.converters = Collections.unmodifiableList(newConverters);
    this.convertersMap = Collections.unmodifiableMap(createConvertersMap(converters));

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
    this.failureConverter = Preconditions.checkNotNull(failureConverter, "failureConverter");
    return this;
  }
}
