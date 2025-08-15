package io.temporal.opentracing.internal;

import io.nexusrpc.OperationException;
import io.nexusrpc.OperationStillRunningException;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.temporal.common.interceptors.NexusServiceClientCallsInterceptor;
import io.temporal.common.interceptors.NexusServiceClientCallsInterceptorBase;
import io.temporal.opentracing.OpenTracingOptions;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Nexus service client interceptor that creates OpenTracing spans and propagates the active span
 * context.
 */
public class OpenTracingNexusServiceClientCallsInterceptor
    extends NexusServiceClientCallsInterceptorBase {
  private final SpanFactory spanFactory;
  private final Tracer tracer;
  private final ContextAccessor contextAccessor;

  public OpenTracingNexusServiceClientCallsInterceptor(
      NexusServiceClientCallsInterceptor next,
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
                    .createClientStartNexusOperationSpan(
                        tracer, input.getServiceName(), input.getOperationName())
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
                    .createClientStartNexusOperationSpan(
                        tracer, input.getServiceName(), input.getOperationName())
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
                    .createClientCancelNexusOperationSpan(
                        tracer, input.getServiceName(), input.getOperationName())
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
                    .createClientCancelNexusOperationSpan(
                        tracer, input.getServiceName(), input.getOperationName())
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
    Span span =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createClientFetchNexusOperationResultSpan(
                        tracer,
                        input.getServiceName(),
                        input.getOperationName(),
                        input.getOperationToken())
                    .start(),
            input.getOptions().getHeaders(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.fetchOperationResult(input);
    } catch (Throwable t) {
      spanFactory.logFail(span, t);
      throw t;
    } finally {
      span.finish();
    }
  }

  @Override
  public FetchOperationInfoOutput fetchOperationInfo(FetchOperationInfoInput input) {
    Span span =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createClientFetchNexusOperationInfoSpan(
                        tracer, input.getServiceName(), input.getOperationName())
                    .start(),
            input.getOptions().getHeaders(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.fetchOperationInfo(input);
    } catch (Throwable t) {
      spanFactory.logFail(span, t);
      throw t;
    } finally {
      span.finish();
    }
  }

  @Override
  public CompleteOperationOutput completeOperation(CompleteOperationInput input) {
    propagate(input.getOptions().getHeaders());
    return super.completeOperation(input);
  }

  @Override
  public CompletableFuture<FetchOperationResultOutput> fetchOperationResultAsync(
      FetchOperationResultInput input) {
    Span span =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createClientFetchNexusOperationResultSpan(
                        tracer,
                        input.getServiceName(),
                        input.getOperationName(),
                        input.getOperationToken())
                    .start(),
            input.getOptions().getHeaders(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.fetchOperationResultAsync(input)
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
  public CompletableFuture<FetchOperationInfoOutput> fetchOperationInfoAsync(
      FetchOperationInfoInput input) {
    Span span =
        contextAccessor.writeSpanContextToHeader(
            () ->
                spanFactory
                    .createClientFetchNexusOperationInfoSpan(
                        tracer, input.getServiceName(), input.getOperationName())
                    .start(),
            input.getOptions().getHeaders(),
            tracer);
    try (Scope ignored = tracer.scopeManager().activate(span)) {
      return super.fetchOperationInfoAsync(input)
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
