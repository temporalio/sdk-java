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

import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.common.converter.Values;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** <b>This exception is expected to be thrown only by the Temporal framework code.</b> */
public final class CanceledFailure extends TemporalFailure {
  private final Values details;

  public CanceledFailure(String message, @Nonnull Values details, @Nullable Throwable cause) {
    super(message, message, cause);
    this.details = Objects.requireNonNull(details);
  }

  public CanceledFailure(String message, Object details) {
    this(message, new EncodedValues(details), null);
  }

  public CanceledFailure(String message) {
    this(message, null);
  }

  public Values getDetails() {
    return details;
  }

  @Override
  public void setDataConverter(DataConverter converter) {
    ((EncodedValues) details).setDataConverter(converter);
  }
}
