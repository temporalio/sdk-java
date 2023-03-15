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

package io.temporal.payload.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.Experimental;
import io.temporal.common.converter.DataConverter;
import io.temporal.payload.context.SerializationContext;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Codec that encodes or decodes the given payloads. {@link PayloadCodec} implementation may be used
 * in {@link io.temporal.common.converter.CodecDataConverter} or server side in a Remote Data
 * Encoder implementation.
 */
public interface PayloadCodec {
  @Nonnull
  List<Payload> encode(@Nonnull List<Payload> payloads);

  @Nonnull
  List<Payload> decode(@Nonnull List<Payload> payloads);

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
  default PayloadCodec withContext(@Nonnull SerializationContext context) {
    return this;
  }
}
