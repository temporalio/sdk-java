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
import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.payload.context.SerializationContext;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nonnull;

/**
 * Used by the framework to serialize/deserialize method parameters that need to be sent over the
 * wire.
 *
 * @author fateev
 */
public interface PayloadConverter {

  /**
   * Each {@link PayloadConverter} has an Encoding Type that it handles. Each {@link
   * PayloadConverter} should add the information about its Encoding Type into the {@link Payload}
   * it produces inside {@link #toData(Object)} by associating it with the {@link
   * EncodingKeys#METADATA_ENCODING_KEY} key attached to the {@code Payload}'s Metadata using {@link
   * Payload.Builder#putMetadata(String, ByteString)}.
   *
   * @return encoding type that this converter handles.
   */
  String getEncodingType();

  /**
   * Implements conversion of a list of values.
   *
   * @param value Java value to convert.
   * @return converted value
   * @throws DataConverterException if conversion of the value passed as parameter failed for any
   *     reason.
   * @see #getEncodingType() getEncodingType javadoc for an important implementation detail
   */
  Optional<Payload> toData(Object value) throws DataConverterException;

  /**
   * Implements conversion of a single value.
   *
   * @param content Serialized value to convert to a Java object.
   * @param valueType type of the value stored in the {@code content}
   * @param valueGenericType generic type of the value stored in the {@code content}
   * @return converted Java object
   * @throws DataConverterException if conversion of the data passed as parameter failed for any
   *     reason.
   */
  <T> T fromData(Payload content, Class<T> valueType, Type valueGenericType)
      throws DataConverterException;

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
  default PayloadConverter withContext(@Nonnull SerializationContext context) {
    return this;
  }
}
