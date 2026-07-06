# Temporal OpenTelemetry module

This module provides OpenTelemetry integration helpers for Temporal Java SDK workers.

The Java SDK metrics API is based on Tally `Scope`. `temporal-opentelemetry` bridges that Tally surface into OpenTelemetry metrics, configures tracing through the SDK OpenTracing interceptor path, and provides shutdown hooks that flush Tally and OpenTelemetry providers without closing application-owned providers.

## Usage

Add `temporal-opentelemetry` next to your Temporal SDK dependency, then configure the SDK builders before creating clients and workers:

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

By default, the helper creates an OpenTelemetry SDK with OTLP metric and trace exporters, W3C trace context propagation, an OpenTelemetry-backed Tally metrics scope, OpenTracing-shim client and worker interceptors, and flush hooks. The Tally hook is registered before the OpenTelemetry provider hook so buffered Temporal metrics are reported before exporters are force-flushed.

The helper defaults the OTLP endpoint from `OTEL_EXPORTER_OTLP_ENDPOINT`, then `http://localhost:4317`. It defaults the service name from `OTEL_SERVICE_NAME`, then `temporal-worker`.

To use an application-owned provider, call `builder.setOpenTelemetry(...)`; in that path, no exporters are created and the helper only installs the metrics scope, interceptors, and flush hook:

```java
OpenTelemetryWorker.configure(
    serviceOptions,
    clientOptions,
    factoryOptions,
    shutdownHooks::add,
    builder -> builder.setOpenTelemetry(openTelemetry));
```

Use `OpenTelemetryWorker.configureMetrics(...)`, `OpenTelemetryWorker.configureTracing(...)`, and `OpenTelemetryWorker.configureFlushHook(...)` when you want to compose metrics, tracing, or provider flushing separately.

Serverless adapters should run registered shutdown hooks before the invocation exits. If a hook implements `TimedShutdownHook`, pass the remaining cleanup timeout to `run(Duration)`.
