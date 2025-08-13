package io.temporal.opentracing.internal;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.NexusServiceClientInterceptor;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.CancelOperationInput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.CancelOperationOutput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.CompleteOperationAsyncInput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.CompleteOperationInput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.CompleteOperationOutput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.FetchOperationInfoInput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.FetchOperationInfoOutput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.FetchOperationResultInput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.FetchOperationResultOutput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.StartOperationInput;
import io.temporal.common.interceptors.NexusServiceClientInterceptor.StartOperationOutput;
import io.temporal.common.interceptors.NexusServiceClientInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Nexus service client interceptor that creates OpenTracing spans and propagates the active span
 * context.
 */
public class OpenTracingNexusServiceClientInterceptor extends NexusServiceClientInterceptorBase {
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTracingNexusServiceClientInterceptor(
      NexusServiceClientInterceptor next,
      OpenTracingOptions options,
      SpanFactory spanFactory,
      ContextAccessor contextAccessor) {
    super(next);
    this.spanFactory = spanFactory;
    this.tracer = options.getTracer();
    this.contextAccessor = contextAccessor;
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    Span span =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createStartNexusOperationSpan(
                        tracer, input.getServiceName(), input.getOperationName(), null, null)
                    .start(),
            input.getOptions().getHeaders(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.startOperation(input);
    } catch (Throwable t) {
      spanFactory.logFail(span, t);
      throw t;
    } finally {
      span.finish();
    }
  }

  @Override
  public CompletableFuture<StartOperationOutput> startOperationAsync(StartOperationInput input) {
    Span span =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createStartNexusOperationSpan(
                        tracer, input.getServiceName(), input.getOperationName(), null, null)
                    .start(),
            input.getOptions().getHeaders(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.startOperationAsync(input)
          .whenComplete(
              (r, e) -> {
                if (e != null) {
                  spanFactory.logFail(span, e);
                }
                span.finish();
              });
    }
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    Span span =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createCancelNexusOperationSpan(
                        tracer, input.getServiceName(), input.getOperationName(), null)
                    .start(),
            input.getOptions().getHeaders(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.cancelOperation(input);
    } catch (Throwable t) {
      spanFactory.logFail(span, t);
      throw t;
    } finally {
      span.finish();
    }
  }

  @Override
  public CompletableFuture<CancelOperationOutput> cancelOperationAsync(CancelOperationInput input) {
    Span span =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createCancelNexusOperationSpan(
                        tracer, input.getServiceName(), input.getOperationName(), null)
                    .start(),
            input.getOptions().getHeaders(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.cancelOperationAsync(input)
          .whenComplete(
              (r, e) -> {
                if (e != null) {
                  spanFactory.logFail(span, e);
                }
                span.finish();
              });
    }
  }

  @Override
  public FetchOperationResultOutput fetchOperationResult(FetchOperationResultInput input)
      throws OperationException, OperationStillRunningException {
    propagate(input.getOptions().getHeaders());
    return super.fetchOperationResult(input);
  }

  @Override
  public FetchOperationInfoOutput fetchOperationInfo(FetchOperationInfoInput input) {
    propagate(input.getOptions().getHeaders());
    return super.fetchOperationInfo(input);
  }

  @Override
  public CompleteOperationOutput completeOperation(CompleteOperationInput input) {
    propagate(input.getOptions().getHeaders());
    return super.completeOperation(input);
  }

  @Override
  public CompletableFuture<FetchOperationResultOutput> fetchOperationResultAsync(
      FetchOperationResultInput input) {
    propagate(input.getOptions().getHeaders());
    return super.fetchOperationResultAsync(input);
  }

  @Override
  public CompletableFuture<FetchOperationInfoOutput> fetchOperationInfoAsync(
      FetchOperationInfoInput input) {
    propagate(input.getOptions().getHeaders());
    return super.fetchOperationInfoAsync(input);
  }

  @Override
  public CompletableFuture<CompleteOperationOutput> completeOperationAsync(
      CompleteOperationAsyncInput input) {
    propagate(input.getOptions().getHeaders());
    return super.completeOperationAsync(input);
  }

  private void propagate(Map<String, String> headers) {
    Span activeSpan = tracer.scopeManager().activeSpan();
    if (activeSpan != null) {
      contextAccessor.writeSpanContextToHeader(activeSpan.context(), headers, tracer);
    }
  }
}
