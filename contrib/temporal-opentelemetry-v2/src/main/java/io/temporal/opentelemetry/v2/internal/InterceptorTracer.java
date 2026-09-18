package io.temporal.opentelemetry.v2.internal;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.temporal.common.interceptors.Header;
import io.temporal.failure.ApplicationErrorCategory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.sync.DestroyWorkflowThreadError;
import java.util.List;
import java.util.Map;

/** Wraps intercepted Temporal calls in a span and propagates it through headers. */
public final class InterceptorTracer {
  private static final String INSTRUMENTATION_NAME = "temporal-sdk-java";

  private final Tracer tracer;
  private final SpanCodec codec;
  private final boolean addTemporalSpans;

  public InterceptorTracer(String headerKey, boolean addTemporalSpans) {
    this.tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION_NAME);
    this.codec = new SpanCodec(headerKey);
    this.addTemporalSpans = addTemporalSpans;
  }

  /**
   * An intercepted call. {@code E} is the checked exception it declares, or {@link
   * RuntimeException} when it declares none.
   */
  @FunctionalInterface
  interface Call<R, E extends Throwable> {
    R call() throws E;
  }

  /** An intercepted call with no result. */
  @FunctionalInterface
  interface VoidCall<E extends Throwable> {
    void call() throws E;
  }

  <R, E extends Throwable> R traceInbound(
      String operation, String name, Attributes attributes, Header header, Call<R, E> call)
      throws E {
    return traceInbound(operation, name, attributes, codec.read(header), call);
  }

  <E extends Throwable> void traceInbound(
      String operation, String name, Attributes attributes, Header header, VoidCall<E> call)
      throws E {
    traceInbound(operation, name, attributes, codec.read(header), asCall(call));
  }

  <R, E extends Throwable> R traceNexusInbound(
      String operation,
      String name,
      Attributes attributes,
      Map<String, String> nexusHeaders,
      Call<R, E> call)
      throws E {
    return traceInbound(operation, name, attributes, codec.read(nexusHeaders), call);
  }

  private <R, E extends Throwable> R traceInbound(
      String operation, String name, Attributes attributes, Context parent, Call<R, E> call)
      throws E {
    try (Scope ignoredParent = parent.makeCurrent()) {
      if (!addTemporalSpans) {
        return call.call();
      }

      Span span = startSpan(operation, name, attributes, SpanKind.SERVER);
      try (Scope ignored = span.makeCurrent()) {
        return run(span, call);
      } finally {
        span.end();
      }
    }
  }

  <R, E extends Throwable> R traceOutbound(
      String operation, String name, Attributes attributes, Header header, Call<R, E> call)
      throws E {
    return traceOutbound(operation, name, attributes, () -> codec.write(header), call);
  }

  void injectOutboundHeader(Header header) {
    codec.write(header);
  }

  void clearOutboundHeader(Header header) {
    codec.clear(header);
  }

  <R, E extends Throwable> R traceOutbound(
      String operation, String name, Attributes attributes, Call<R, E> call) throws E {
    return traceOutbound(operation, name, attributes, () -> {}, call);
  }

  <E extends Throwable> void traceOutbound(
      String operation, String name, Attributes attributes, Header header, VoidCall<E> call)
      throws E {
    traceOutbound(operation, name, attributes, () -> codec.write(header), asCall(call));
  }

  <R, E extends Throwable> R traceOutbound(
      String operation, String name, Attributes attributes, List<Header> headers, Call<R, E> call)
      throws E {
    return traceOutbound(operation, name, attributes, () -> headers.forEach(codec::write), call);
  }

  <R, E extends Throwable> R traceNexusOutbound(
      String operation,
      String name,
      Attributes attributes,
      Map<String, String> nexusHeaders,
      Call<R, E> call)
      throws E {
    return traceOutbound(operation, name, attributes, () -> codec.write(nexusHeaders), call);
  }

  private <R, E extends Throwable> R traceOutbound(
      String operation, String name, Attributes attributes, Runnable writeHeader, Call<R, E> call)
      throws E {
    if (!addTemporalSpans) {
      writeHeader.run();
      return call.call();
    }

    Span span = startSpan(operation, name, attributes, SpanKind.CLIENT);
    try (Scope ignored = span.makeCurrent()) {
      writeHeader.run();
      return run(span, call);
    } finally {
      span.end();
    }
  }

  private static <E extends Throwable> Call<Void, E> asCall(VoidCall<E> call) {
    return () -> {
      call.call();
      return null;
    };
  }

  /** Records a failure on {@code span} before letting it propagate. */
  private static <R, E extends Throwable> R run(Span span, Call<R, E> call) throws E {
    try {
      return call.call();
    } catch (DestroyWorkflowThreadError unwind) {
      throw unwind;
    } catch (Throwable failure) {
      span.recordException(failure);
      if (!isBenign(failure)) {
        span.setStatus(StatusCode.ERROR, failure.toString());
      }
      throw failure;
    }
  }

  private Span startSpan(String operation, String name, Attributes attributes, SpanKind kind) {
    try (Scope ignored =
        Context.current().with(ReplaySafeIdGenerator.INTERCEPTOR_SPAN, true).makeCurrent()) {
      return tracer
          .spanBuilder(spanName(operation, name))
          .setSpanKind(kind)
          .setAllAttributes(attributes)
          .startSpan();
    }
  }

  static String spanName(String operation, String name) {
    if (operation.isEmpty()) {
      return name;
    }
    if (name.isEmpty()) {
      return operation;
    }
    return operation + ":" + name;
  }

  private static boolean isBenign(Throwable failure) {
    return failure instanceof ApplicationFailure
        && ((ApplicationFailure) failure).getCategory() == ApplicationErrorCategory.BENIGN;
  }
}
