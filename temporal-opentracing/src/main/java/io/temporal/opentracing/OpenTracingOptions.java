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

import com.google.common.base.MoreObjects;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import io.temporal.opentracing.internal.ActionTypeAndNameSpanBuilderProvider;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OpenTracingOptions {
  private static final OpenTracingOptions DEFAULT_INSTANCE =
      OpenTracingOptions.newBuilder().build();

  private final Tracer tracer;
  private final SpanBuilderProvider spanBuilderProvider;
  private final OpenTracingSpanContextCodec spanContextCodec;

  public static OpenTracingOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private OpenTracingOptions(
      Tracer tracer,
      SpanBuilderProvider spanBuilderProvider,
      OpenTracingSpanContextCodec spanContextCodec) {
    if (tracer == null) throw new IllegalArgumentException("tracer shouldn't be null");
    this.tracer = tracer;
    this.spanBuilderProvider = spanBuilderProvider;
    this.spanContextCodec = spanContextCodec;
  }

  @Nonnull
  public Tracer getTracer() {
    return tracer;
  }

  @Nonnull
  public SpanBuilderProvider getSpanBuilderProvider() {
    return spanBuilderProvider;
  }

  @Nonnull
  public OpenTracingSpanContextCodec getSpanContextCodec() {
    return spanContextCodec;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private Tracer tracer;
    private SpanBuilderProvider spanBuilderProvider = ActionTypeAndNameSpanBuilderProvider.INSTANCE;
    private OpenTracingSpanContextCodec spanContextCodec =
        OpenTracingSpanContextCodec.TEXT_MAP_INJECT_EXTRACT_CODEC;

    private Builder() {}

    public Builder setTracer(@Nullable Tracer tracer) {
      this.tracer = tracer;
      return this;
    }

    /**
     * @param spanBuilderProvider custom {@link SpanBuilderProvider}, allows for more control over
     *     how OpenTracing spans are created, named, and tagged.
     * @return this
     */
    public Builder setSpanBuilderProvider(@Nonnull SpanBuilderProvider spanBuilderProvider) {
      Objects.requireNonNull(spanBuilderProvider, "spanBuilderProvider can't be null");
      this.spanBuilderProvider = spanBuilderProvider;
      return this;
    }

    /**
     * @param spanContextCodec custom {@link OpenTracingSpanContextCodec}, allows for more control
     *     over how SpanContext is encoded and decoded from Map
     * @return this
     */
    public Builder setSpanContextCodec(@Nonnull OpenTracingSpanContextCodec spanContextCodec) {
      Objects.requireNonNull(spanContextCodec, "spanContextCodec can't be null");
      this.spanContextCodec = spanContextCodec;
      return this;
    }

    public OpenTracingOptions build() {
      return new OpenTracingOptions(
          MoreObjects.firstNonNull(tracer, GlobalTracer.get()),
          spanBuilderProvider,
          spanContextCodec);
    }
  }
}
