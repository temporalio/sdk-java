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

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import java.lang.reflect.Type;
import java.util.Optional;

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
}
