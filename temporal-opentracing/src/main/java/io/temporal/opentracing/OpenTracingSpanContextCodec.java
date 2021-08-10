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
