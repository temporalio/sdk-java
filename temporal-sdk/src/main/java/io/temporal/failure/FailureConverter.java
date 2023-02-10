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

package io.temporal.failure;

import io.temporal.api.failure.v1.Failure;
import io.temporal.common.converter.DataConverter;
import javax.annotation.Nonnull;

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
  RuntimeException failureToException(
      @Nonnull Failure failure, @Nonnull DataConverter dataConverter);

  /**
   * Serialize an existing Java Exception into a Failure object.
   *
   * @param e A Java Exception object to serialize into a Failure protobuf object
   * @param dataConverter to be used to convert {@code Failure#encodedAttributes} and {@code
   *     Failure#failure_info#details} (if present).
   * @return serialized exception
   * @throws NullPointerException if either e or dataConverter is null
   */
  @Nonnull
  Failure exceptionToFailure(@Nonnull Throwable e, @Nonnull DataConverter dataConverter);
}
