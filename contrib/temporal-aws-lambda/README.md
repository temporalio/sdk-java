# Temporal AWS Lambda worker module

This module provides a direct AWS Lambda Java handler for running a Temporal worker for one Lambda invocation.

## Usage

Add `temporal-aws-lambda` next to your Temporal SDK dependency, then expose the returned handler from your Lambda class:

```java
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import io.temporal.aws.lambda.LambdaWorker;
import io.temporal.common.WorkerDeploymentVersion;

public final class Handler implements RequestHandler<Object, Void> {
  private static final RequestHandler<Object, Void> WORKER =
	      LambdaWorker.run(
	          new WorkerDeploymentVersion("orders-worker", "2026-06-02"),
	          builder ->
	              builder
	                  .setTaskQueue("orders")
	                  .registerWorkflowImplementationTypes(OrderWorkflowImpl.class)
	                  .registerActivitiesImplementations(new OrderActivitiesImpl()));

  @Override
  public Void handleRequest(Object input, Context context) {
    return WORKER.handleRequest(input, context);
  }
}
```

`TEMPORAL_TASK_QUEUE` can provide the task queue. If it is not set, call `setTaskQueue`.

Connection options are loaded with `temporal-envconfig` when the handler is constructed during Lambda cold start. The Lambda worker checks `TEMPORAL_CONFIG_FILE` first, then readable `$LAMBDA_TASK_ROOT/temporal.toml`, then readable `./temporal.toml`, then falls back to the envconfig defaults and Temporal environment variables. The `configure` callback also runs during handler construction, so non-invocation configuration is prepared once and reused.

If you need to assemble options outside the `run` callback, call `LambdaWorkerOptions.newBuilderFromEnvironment()`, configure the returned builder, call `build()`, and pass the options to `LambdaWorker.newHandler(...)`.

Dynamic workflow and activity implementations can be registered with `registerDynamicWorkflowImplementationType(...)` and `registerDynamicActivityImplementation(...)`. Java SDK worker rules still apply: only one dynamic workflow implementation type and one dynamic activity implementation can be registered per worker.

The handler creates one worker per invocation, starts the worker, shuts it down before the Lambda deadline, runs shutdown hooks in order, and closes service stubs. Worker deployment versioning is always enabled for the supplied `WorkerDeploymentVersion`. If neither client nor worker identity is set by the user, each invocation uses `<awsRequestId>@<invokedFunctionArn>` as the Temporal identity.

`shutdownDeadlineBuffer` is the full shutdown window reserved at the end of the Lambda invocation. The default is 7 seconds: 5 seconds for `gracefulShutdownTimeout` and 2 seconds for hooks and service stubs. The worker runs until `remainingTime - shutdownDeadlineBuffer`, then stops and awaits termination for `gracefulShutdownTimeout`. If you change `gracefulShutdownTimeout` without explicitly setting `shutdownDeadlineBuffer`, the buffer is recomputed as `gracefulShutdownTimeout + 2s`.
If you explicitly set `shutdownDeadlineBuffer`, it must be greater than or equal to `gracefulShutdownTimeout`.

## OpenTelemetry

`OtelLambdaWorker.configure(builder)` creates an OpenTelemetry SDK with OTLP metric and trace exporters by default, uses AWS X-Ray-compatible trace ID generation, installs an OpenTelemetry-backed Tally metrics scope, configures tracing through the SDK OpenTracing interceptor path, and registers per-invocation flush hooks. The metrics hook reports buffered Tally values before the OpenTelemetry provider hook force-flushes exporters. To enable it, call the helper from the handler initializer:

```java
private static final RequestHandler<Object, Void> WORKER =
	    LambdaWorker.run(
	        new WorkerDeploymentVersion("orders-worker", "2026-06-02"),
	        builder -> {
	          OtelLambdaWorker.configure(builder);
	          builder
	              .setTaskQueue("orders")
	              .registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
	        });
```

The helper defaults the OTLP endpoint from `OTEL_EXPORTER_OTLP_ENDPOINT`, then `http://localhost:4317`. It defaults the service name from `OTEL_SERVICE_NAME`, then `AWS_LAMBDA_FUNCTION_NAME`, then `temporal-lambda-worker`, and sets it on the OpenTelemetry resource. To use an application-owned provider, call `builder.setOpenTelemetry(...)`; in that path, no exporters are created and the helper only installs the metrics scope, interceptors, and per-invocation flush hook. Providers and scopes are not closed after each invocation.

Use `OtelLambdaWorker.configureMetrics(...)`, `OtelLambdaWorker.configureTracing(...)`, and `OtelLambdaWorker.configureFlushHook(...)` when you want to compose metrics, tracing, or provider flushing separately around an application-owned OpenTelemetry instance.

For Java logging, this module depends on `slf4j-api` only. It does not bundle a runtime logging binding, so Lambda log formatting remains owned by the application.
