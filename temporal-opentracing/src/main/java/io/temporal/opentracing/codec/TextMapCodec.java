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

package io.temporal.opentracing.codec;

import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.temporal.opentracing.OpenTracingSpanContextCodec;
import java.util.HashMap;
import java.util.Map;

/**
 * This Encoder uses {@link io.opentracing.propagation.Format.Builtin#TEXT_MAP} for both
 * serialization and deserialization. This is not a default strategy, for default strategy see
 * {@link TextMapInjectExtractCodec}. This strategy was added as a workaround if the specific
 * opentracing client implementation doesn't support {@link
 * io.opentracing.propagation.Format.Builtin#TEXT_MAP_INJECT} and {@link
 * io.opentracing.propagation.Format.Builtin#TEXT_MAP_EXTRACT}
 */
public class TextMapCodec implements OpenTracingSpanContextCodec {
  public static final TextMapCodec INSTANCE = new TextMapCodec();

  @Override
  public Map<String, String> encode(SpanContext spanContext, Tracer tracer) {
    Map<String, String> serialized = new HashMap<>();
    tracer.inject(spanContext, Format.Builtin.TEXT_MAP, new TextMapAdapter(serialized));
    return serialized;
  }

  @Override
  public SpanContext decode(Map<String, String> serializedSpanContext, Tracer tracer) {
    return tracer.extract(Format.Builtin.TEXT_MAP, new TextMapAdapter(serializedSpanContext));
  }
}
