package io.temporal.common.interceptors;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This is a "better" implementation, that will give right behavior.
 * Requires getOptions setOptions on stub to hack it right before the call.
 * Check out {@link SampleOpenTracingLikeInterceptor} before this class.
 */
public class SampleOpenTracingLikeInterceptor2 extends WorkflowClientInterceptorBase {
  @Override
  public WorkflowStub newUntypedWorkflowStub(
      String workflowType, WorkflowOptions options, WorkflowStub next) {
    return new WorkflowStub() {
      private void hackHeaders() {
        //We shouldn't use headers that are passed as a parameter to newUntypedWorkflowStub, because
        //they can be outdated at a time of call because of the exposed setOptions
        WorkflowOptions options = next.getOptions().orElse(null);
        Map<String, Object> originalHeaders = options != null ? options.getHeaders() : null;
        Map<String, Object> newHeaders;

        if (originalHeaders == null) {
          newHeaders = new HashMap<>();
        } else {
          newHeaders = new HashMap<>(originalHeaders);
        }
        newHeaders.put("opentracing", new Object());
        WorkflowOptions modifiedOptions =
                WorkflowOptions.newBuilder(options).setHeaders(newHeaders).build();
        next.setOptions(modifiedOptions);
      }

      @Override
      public WorkflowExecution start(Object... args) {
        hackHeaders();
        return next.start(args);
      }

      @Override
      public WorkflowExecution signalWithStart(String signalName, Object[] signalArgs, Object[] startArgs) {
        hackHeaders();
        return next.signalWithStart(signalName, signalArgs, startArgs);
      }

      @Override
      public void signal(String signalName, Object... args) {
        next.signal(signalName, args);
      }

      @Override
      public Optional<String> getWorkflowType() {
        return next.getWorkflowType();
      }

      @Override
      public WorkflowExecution getExecution() {
        return next.getExecution();
      }

      @Override
      public <R> R getResult(Class<R> resultClass, Type resultType) {
        return next.getResult(resultClass, resultType);
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass, Type resultType) {
        return next.getResultAsync(resultClass, resultType);
      }

      @Override
      public <R> R getResult(Class<R> resultClass) {
        return next.getResult(resultClass);
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(Class<R> resultClass) {
        return next.getResultAsync(resultClass);
      }

      @Override
      public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass, Type resultType) throws TimeoutException {
        return next.getResult(timeout, unit, resultClass, resultType);
      }

      @Override
      public <R> R getResult(long timeout, TimeUnit unit, Class<R> resultClass) throws TimeoutException {
        return next.getResult(timeout, unit, resultClass);
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit, Class<R> resultClass, Type resultType) {
        return next.getResultAsync(timeout, unit, resultClass, resultType);
      }

      @Override
      public <R> CompletableFuture<R> getResultAsync(long timeout, TimeUnit unit, Class<R> resultClass) {
        return next.getResultAsync(timeout, unit, resultClass);
      }

      @Override
      public <R> R query(String queryType, Class<R> resultClass, Object... args) {
        return next.query(queryType, resultClass, args);
      }

      @Override
      public <R> R query(String queryType, Class<R> resultClass, Type resultType, Object... args) {
        return next.query(queryType, resultClass, resultType, args);
      }

      @Override
      public void cancel() {
        next.cancel();
      }

      @Override
      public void terminate(String reason, Object... details) {
        next.terminate(reason, details);
      }

      @Override
      public Optional<WorkflowOptions> getOptions() {
        return next.getOptions();
      }

      @Override
      public void setOptions(WorkflowOptions options) {
        next.setOptions(options);
      }
    };
  }
}
