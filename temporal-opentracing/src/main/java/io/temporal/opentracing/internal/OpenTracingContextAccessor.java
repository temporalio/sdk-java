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

package io.temporal.opentracing.internal;

import com.google.common.reflect.TypeToken;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.Header;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

class OpenTracingContextAccessor {
  private static final String TRACER_HEADER_KEY = "_tracer-data";
  private static final Type HASH_MAP_STRING_STRING_TYPE =
      new TypeToken<HashMap<String, String>>() {}.getType();

  public static void writeSpanContextToHeader(
      SpanContext spanContext, Header header, Tracer tracer) {
    Map<String, String> serializedSpanContext = serializeSpanContextToMap(spanContext, tracer);
    Optional<Payload> payload = DataConverter.getDefaultInstance().toPayload(serializedSpanContext);
    header.getValues().put(TRACER_HEADER_KEY, payload.get());
  }

  private static Map<String, String> serializeSpanContextToMap(
      SpanContext spanContext, Tracer tracer) {
    Map<String, String> serialized = new HashMap<>();
    tracer.inject(
        spanContext, Format.Builtin.TEXT_MAP_INJECT, new TextMapInjectAdapter(serialized));
    return serialized;
  }

  public static SpanContext readSpanContextFromHeader(Header header, Tracer tracer) {
    Payload payload = header.getValues().get(TRACER_HEADER_KEY);
    if (payload == null) {
      return null;
    }
    Map<String, String> serializedSpanContext =
        DataConverter.getDefaultInstance()
            .fromPayload(payload, HashMap.class, HASH_MAP_STRING_STRING_TYPE);
    return deserializeSpanContextFromMap(serializedSpanContext, tracer);
  }

  private static SpanContext deserializeSpanContextFromMap(
      Map<String, String> serializedSpanContext, Tracer tracer) {
    return tracer.extract(
        Format.Builtin.TEXT_MAP_EXTRACT, new TextMapExtractAdapter(serializedSpanContext));
  }
}
