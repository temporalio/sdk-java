# Temporal OpenTelemetry module

This module provides OpenTelemetry integration helpers for Temporal Java SDK workers.

The Java SDK metrics API is based on Tally `Scope`. `temporal-opentelemetry` bridges that Tally surface into OpenTelemetry metrics, configures tracing through the SDK OpenTracing interceptor path, and provides shutdown hooks that flush Tally and OpenTelemetry providers without closing application-owned providers.

## Usage

Add `temporal-opentelemetry` next to your Temporal SDK dependency, then install the plugin on service stubs options before creating clients and workers:

```java
OpenTelemetryPlugin plugin = OpenTelemetryPlugin.newBuilder().build();

WorkflowServiceStubs service =
    WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder()
            .setPlugins(plugin)
            .build());
WorkflowClient client = WorkflowClient.newInstance(service);
WorkerFactory factory = WorkerFactory.newInstance(client);
```

Plugins configured on service stubs propagate to workflow clients and worker factories. By default, the plugin creates an OpenTelemetry SDK with OTLP metric and trace exporters, W3C trace context propagation, an OpenTelemetry-backed Tally metrics scope, OpenTracing-shim client and worker interceptors, and a worker-factory shutdown flush. Buffered Tally metrics are reported before OpenTelemetry providers are force-flushed. Providers are not closed.

The plugin defaults the OTLP endpoint from `OTEL_EXPORTER_OTLP_ENDPOINT`, then `http://localhost:4317`. It defaults the service name from `OTEL_SERVICE_NAME`, then `temporal-worker`.

To use an application-owned provider, call `builder.setOpenTelemetry(...)`; in that path, no exporters are created and the plugin only installs the metrics scope, interceptors, and flush hook:

```java
OpenTelemetryPlugin plugin =
    OpenTelemetryPlugin.newBuilder()
        .setOpenTelemetry(openTelemetry)
        .build();
```

Serverless adapters should disable worker-factory shutdown flushing and run `plugin.newFlushHook()` from their per-invocation cleanup path. If a hook implements `TimedShutdownHook`, pass the remaining cleanup timeout to `run(Duration)`.

For manual composition or existing integrations, `OpenTelemetryWorker.configure(...)` remains available:

```java
List<Runnable> shutdownHooks = new ArrayList<>();

WorkflowServiceStubsOptions.Builder serviceOptions = WorkflowServiceStubsOptions.newBuilder();
WorkflowClientOptions.Builder clientOptions = WorkflowClientOptions.newBuilder();
WorkerFactoryOptions.Builder factoryOptions = WorkerFactoryOptions.newBuilder();

OpenTelemetryWorker.configure(
    serviceOptions,
    clientOptions,
    factoryOptions,
    shutdownHooks::add);
```

Use `OpenTelemetryWorker.configureMetrics(...)`, `OpenTelemetryWorker.configureTracing(...)`, and `OpenTelemetryWorker.configureFlushHook(...)` when you want to compose metrics, tracing, or provider flushing separately.

To use an application-owned provider with the manual helper:

```java
OpenTelemetryWorker.configure(
    serviceOptions,
    clientOptions,
    factoryOptions,
    shutdownHooks::add,
    builder -> builder.setOpenTelemetry(openTelemetry));
```
