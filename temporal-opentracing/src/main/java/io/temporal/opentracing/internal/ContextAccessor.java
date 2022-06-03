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

package io.temporal.opentracing.internal;

import com.google.common.reflect.TypeToken;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.opentracing.OpenTracingOptions;
import io.temporal.opentracing.OpenTracingSpanContextCodec;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class ContextAccessor {
  private static final String TRACER_HEADER_KEY = "_tracer-data";
  private static final Type HASH_MAP_STRING_STRING_TYPE =
      new TypeToken<HashMap<String, String>>() {}.getType();

  private final OpenTracingSpanContextCodec codec;

  public ContextAccessor(OpenTracingOptions options) {
    this.codec = options.getSpanContextCodec();
  }

  public Span writeSpanContextToHeader(
      Supplier<Span> spanSupplier, Header toHeader, Tracer tracer) {
    Span span = spanSupplier.get();
    writeSpanContextToHeader(span.context(), toHeader, tracer);
    return span;
  }

  public void writeSpanContextToHeader(SpanContext spanContext, Header header, Tracer tracer) {
    Map<String, String> serializedSpanContext = codec.encode(spanContext, tracer);
    Optional<Payload> payload = DataConverter.getDefaultInstance().toPayload(serializedSpanContext);
    header.getValues().put(TRACER_HEADER_KEY, payload.get());
  }

  public SpanContext readSpanContextFromHeader(Header header, Tracer tracer) {
    Payload payload = header.getValues().get(TRACER_HEADER_KEY);
    if (payload == null) {
      return null;
    }
    @SuppressWarnings("unchecked")
    Map<String, String> serializedSpanContext =
        DataConverter.getDefaultInstance()
            .fromPayload(payload, HashMap.class, HASH_MAP_STRING_STRING_TYPE);
    return codec.decode(serializedSpanContext, tracer);
  }
}
