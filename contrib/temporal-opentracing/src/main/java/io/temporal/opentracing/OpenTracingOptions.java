package io.temporal.opentracing;

import com.google.common.base.MoreObjects;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import io.temporal.opentracing.internal.ActionTypeAndNameSpanBuilderProvider;
import java.util.Objects;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class OpenTracingOptions {
  private static final OpenTracingOptions DEFAULT_INSTANCE =
      OpenTracingOptions.newBuilder().build();

  private final Tracer tracer;
  private final SpanBuilderProvider spanBuilderProvider;
  private final OpenTracingSpanContextCodec spanContextCodec;
  private final Predicate<Throwable> isErrorPredicate;
  private final boolean encodeHeaders;

  public static OpenTracingOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private OpenTracingOptions(
      Tracer tracer,
      SpanBuilderProvider spanBuilderProvider,
      OpenTracingSpanContextCodec spanContextCodec,
      Predicate<Throwable> isErrorPredicate,
      boolean encodeHeaders) {
    if (tracer == null) throw new IllegalArgumentException("tracer shouldn't be null");
    this.tracer = tracer;
    this.spanBuilderProvider = spanBuilderProvider;
    this.spanContextCodec = spanContextCodec;
    this.isErrorPredicate = isErrorPredicate;
    this.encodeHeaders = encodeHeaders;
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

  @Nonnull
  public Predicate<Throwable> getIsErrorPredicate() {
    return isErrorPredicate;
  }

  public boolean isEncodeHeaders() {
    return encodeHeaders;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    private Tracer tracer;
    private SpanBuilderProvider spanBuilderProvider = ActionTypeAndNameSpanBuilderProvider.INSTANCE;
    private OpenTracingSpanContextCodec spanContextCodec =
        OpenTracingSpanContextCodec.TEXT_MAP_INJECT_EXTRACT_CODEC;
    private Predicate<Throwable> isErrorPredicate = t -> true;
    private boolean encodeHeaders;

    private Builder() {}

    /**
     * Uses the configured client's payload codec for this integration's tracing headers. Defaults
     * to false. Standard tracing serialization and other producers' headers are unchanged.
     * Configure the same setting on receiving workers. Codecs must pass through unrecognized
     * encodings.
     */
    public Builder setEncodeHeaders(boolean encodeHeaders) {
      this.encodeHeaders = encodeHeaders;
      return this;
    }

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

    /**
     * @param isErrorPredicate indicates whether the received exception should cause the OpenTracing
     *     span to finish in an error state or not. All exceptions will be logged to the span
     *     regardless, in order to provide a complete picture of the execution outcome. The "error"
     *     tag on the span will be set according to the value returned by this Predicate. By
     *     default, all exceptions will be considered errors.
     * @see <a
     *     href="https://github.com/opentracing/specification/blob/master/semantic_conventions.md#span-and-log-errors">
     *     OpenTracing Documentation</a> regarding error spans and logging exceptions.
     * @return this
     */
    public Builder setIsErrorPredicate(@Nonnull Predicate<Throwable> isErrorPredicate) {
      Objects.requireNonNull(isErrorPredicate, "isErrorPredicate can't be null");
      this.isErrorPredicate = isErrorPredicate;
      return this;
    }

    public OpenTracingOptions build() {
      return new OpenTracingOptions(
          MoreObjects.firstNonNull(tracer, GlobalTracer.get()),
          spanBuilderProvider,
          spanContextCodec,
          isErrorPredicate,
          encodeHeaders);
    }
  }
}
