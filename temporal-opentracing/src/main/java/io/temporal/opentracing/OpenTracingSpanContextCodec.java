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

package io.temporal.opentracing;

import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.temporal.opentracing.codec.TextMapCodec;
import io.temporal.opentracing.codec.TextMapInjectExtractCodec;
import java.util.Map;

/**
 * Provides an interface for pluggable implementations that provide encoding/decoding of OpenTracing
 * {@link SpanContext}s back and forth to {@code Map<String, String>} that can be transported inside
 * Temporal Headers
 */
public interface OpenTracingSpanContextCodec {
  // default implementation
  OpenTracingSpanContextCodec TEXT_MAP_INJECT_EXTRACT_CODEC = TextMapInjectExtractCodec.INSTANCE;
  OpenTracingSpanContextCodec TEXT_MAP_CODEC = TextMapCodec.INSTANCE;

  Map<String, String> encode(SpanContext spanContext, Tracer tracer);

  SpanContext decode(Map<String, String> serializedSpanContext, Tracer tracer);
}
