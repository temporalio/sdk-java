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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Defaults;
import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.Experimental;
import io.temporal.failure.DefaultFailureConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Used by the framework to serialize/deserialize method parameters that need to be sent over the
 * wire.
 *
 * <h2>Most users should never implement this interface until absolutely needed.</h2>
 *
 * Instead, users should implement
 *
 * <ul>
 *   <li>{@link PayloadConverter} to customize Object &lt;-&gt; Payload (bytes) conversion
 *   <li>{@link PayloadCodec} to perform Payload (bytes) &lt;-&gt; Payload (bytes) encoding (like
 *       encryption or compression)
 * </ul>
 *
 * A custom {@link PayloadConverter} can be registered on {@link DefaultDataConverter} instance. For
 * that:
 *
 * <ul>
 *   <li>Obtain {@link DefaultDataConverter} instance from {@link
 *       DefaultDataConverter#newDefaultInstance()}. Register your custom {@link PayloadConverter}
 *       using {@link DefaultDataConverter#withPayloadConverterOverrides(PayloadConverter...)}. This
 *       way will preserve the standard set of {@link PayloadConverter}s supplied by Temporal
 *       JavaSDK other that the ones that were overridden. See {@link
 *       DefaultDataConverter#STANDARD_PAYLOAD_CONVERTERS})
 *   <li>Pass the custom {@link PayloadConverter} directly to {@link
 *       DefaultDataConverter#DefaultDataConverter(PayloadConverter...)} to discard the standard
 *       {@link PayloadConverter}s supplied by Temporal JavaSDK out of the box.
 * </ul>
 *
 * A {@link DataConverter} created on previous step may be bundled with {@link PayloadCodec}s using
 * {@link CodecDataConverter} or used directly if no custom {@link PayloadCodec}s are needed.
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
   * Implements conversion of a single {@link Payload} from the serialized {@link Payloads}.
   *
   * @param index index of the value in the payloads
   * @param content serialized value to convert to Java objects.
   * @param valueType type of the value stored in the {@code content}
   * @param valueGenericType generic type of the value stored in the {@code content}
   * @return converted Java object
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  <T> T fromPayloads(
      int index, Optional<Payloads> content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException;

  /**
   * Implements conversion of the whole {@code content} {@link Payloads} into an array of values of
   * different types.
   *
   * <p>Implementation note<br>
   * This method is expected to return an array of the same length as {@code parameterTypes}. If
   * {@code content} has not enough {@link Payload} elements, this method provides default
   * instances.
   *
   * @param content serialized value to convert to Java objects.
   * @param parameterTypes types of the values stored in the @code content}
   * @param genericParameterTypes generic types of the values stored in the {@code content}
   * @return array if converted Java objects
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  default Object[] fromPayloads(
      Optional<Payloads> content, Class<?>[] parameterTypes, Type[] genericParameterTypes)
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

    int totalLength = parameterTypes.length;
    Object[] result = new Object[totalLength];
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
        result[i] = this.fromPayload(payloads.getPayloads(i), pt, gt);
      }
    }
    return result;
  }

  /**
   * Instantiate an appropriate Java Exception from a serialized Failure object. The default
   * implementation delegates the conversion process to an instance of {@link FailureConverter},
   * using this data converter for payload decoding.
   *
   * @param failure Failure protobuf object to deserialize into an exception
   * @throws NullPointerException if failure is null
   */
  @Nonnull
  default TemporalFailure failureToException(@Nonnull Failure failure) {
    Preconditions.checkNotNull(failure, "failure");
    return new DefaultFailureConverter().failureToException(failure, this);
  }

  /**
   * Serialize an existing Throwable object into a Failure object. The default implementation
   * delegates the conversion process to an instance of {@link FailureConverter}, using this data
   * converter for payload encoding.
   *
   * @param throwable a Throwable object to serialize into a Failure protobuf object
   * @throws NullPointerException if throwable is null
   */
  @Nonnull
  default Failure exceptionToFailure(@Nonnull Throwable throwable) {
    Preconditions.checkNotNull(throwable, "throwable");
    return new DefaultFailureConverter().exceptionToFailure(throwable, this);
  }

  /**
   * A correct implementation of this interface should have a fully functional "contextless"
   * implementation. Temporal SDK will call this method when a knowledge of the context exists, but
   * {@link DataConverter} can be used directly by user code and sometimes SDK itself without any
   * context.
   *
   * <p>Note: this method is expected to be cheap and fast. Temporal SDK doesn't always cache the
   * instances and may be calling this method very often. Users are responsible to make sure that
   * this method doesn't recreate expensive objects like Jackson's {@link ObjectMapper} on every
   * call.
   *
   * @param context provides information to the data converter about the abstraction the data
   *     belongs to
   * @return an instance of DataConverter that may use the provided {@code context} for
   *     serialization
   * @see SerializationContext
   */
  @Experimental
  @Nonnull
  default DataConverter withContext(@Nonnull SerializationContext context) {
    return this;
  }

  /**
   * @deprecated use {@link DataConverter#fromPayloads(int, Optional, Class, Type)}. This is an SDK
   *     implementation detail and never was expected to be exposed to users.
   */
  @Deprecated
  static Object[] arrayFromPayloads(
      DataConverter converter,
      Optional<Payloads> content,
      Class<?>[] parameterTypes,
      Type[] genericParameterTypes)
      throws DataConverterException {
    return converter.fromPayloads(content, parameterTypes, genericParameterTypes);
  }
}
