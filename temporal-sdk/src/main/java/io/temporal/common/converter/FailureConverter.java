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

import io.temporal.api.failure.v1.Failure;
import io.temporal.failure.DefaultFailureConverter;
import io.temporal.failure.TemporalFailure;
import io.temporal.payload.context.SerializationContext;
import javax.annotation.Nonnull;

/**
 * A {@code FailureConverter} is responsible for converting from proto {@link Failure} instances to
 * Java {@link Exception}, and back.
 *
 * <p>Most users should _never_ need to implement a failure converter. We strongly recommended
 * relying on the {@link DefaultFailureConverter}, in order to maintain cross-language Failure
 * serialization compatibility.
 *
 * <p>To _encrypt_ the content of failures, see {@link
 * io.temporal.common.converter.CodecDataConverter} instead.
 */
public interface FailureConverter {

  /**
   * Instantiate an appropriate Java Exception from a serialized Failure object.
   *
   * @param failure Failure protobuf object to deserialize into an exception
   * @param dataConverter to be used to convert {@code Failure#encodedAttributes} and {@code
   *     Failure#failure_info#details} (if present).
   * @return deserialized exception
   * @throws NullPointerException if either failure or dataConverter is null
   */
  @Nonnull
  TemporalFailure failureToException(
      @Nonnull Failure failure, @Nonnull DataConverter dataConverter);

  /**
   * Serialize an existing Java Exception into a Failure object.
   *
   * @param throwable A Java Exception object to serialize into a Failure protobuf object
   * @param dataConverter to be used to convert {@code Failure#encodedAttributes} and {@code
   *     Failure#failure_info#details} (if present).
   * @return serialized exception
   * @throws NullPointerException if either e or dataConverter is null
   */
  @Nonnull
  Failure exceptionToFailure(@Nonnull Throwable throwable, @Nonnull DataConverter dataConverter);

  default @Nonnull FailureConverter withContext(@Nonnull SerializationContext context) {
    return this;
  }
}
