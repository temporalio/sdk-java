# Temporal Google Cloud module

This module provides an OpenTelemetry plugin with defaults for Temporal Java SDK workers running on Google Cloud Run. Cloud Run worker pools are the recommended deployment because Temporal workers are continuous, pull-based background workloads.

> **Collector required by default:** The plugin exports metrics and traces to an OTLP collector at `http://localhost:4317`. It does not export directly to Google Cloud. Deploy the Google-Built OpenTelemetry Collector as a sidecar, configure another collector endpoint, or provide an application-owned `OpenTelemetry` instance. Without a collector at the configured endpoint, telemetry is not delivered to Google Cloud.

This integration is for container-based Cloud Run workloads. It does not implement a Cloud Run functions invocation lifecycle.

A Cloud Run service can also host a Temporal worker, but it must use instance-based billing so CPU is available outside request handling, keep at least one instance active through minimum instances or manual scaling, and run an ingress container that listens on `PORT`. These are deployment requirements; the plugin cannot configure them from inside the worker process.

## Usage

Add `temporal-gcp` next to your Temporal SDK dependency, then install the plugin on service stubs options before creating clients and workers:

```java
GcpOpenTelemetryPlugin plugin = GcpOpenTelemetryPlugin.newBuilder().build();

WorkflowServiceStubs service =
    WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder()
            .setPlugins(plugin)
            .build());
WorkflowClient client = WorkflowClient.newInstance(service);
WorkerFactory factory = WorkerFactory.newInstance(client);
```

The plugin configures the SDK metrics scope, tracing interceptors, and OTLP metric and trace exporters through `temporal-opentelemetry`. Do not install both `GcpOpenTelemetryPlugin` and `OpenTelemetryPlugin` on the same service stubs.

## Shutdown lifecycle

`WorkerFactory.shutdown()` initiates asynchronous shutdown. The GCP plugin therefore does not flush from the worker-factory shutdown callback by default: doing so could miss telemetry emitted while activities and workflows finish. Wait for the factory to terminate, then flush with the time remaining before Cloud Run sends `SIGKILL`. For example, the following JVM shutdown hook reserves six seconds for graceful shutdown, one second for forced shutdown, and two seconds for telemetry flushing within Cloud Run's ten-second termination window:

```java
Runtime.getRuntime()
    .addShutdownHook(
        new Thread(
            () -> {
              factory.shutdown();
              factory.awaitTermination(6, TimeUnit.SECONDS);
              if (!factory.isTerminated()) {
                factory.shutdownNow();
                factory.awaitTermination(1, TimeUnit.SECONDS);
              }
              plugin.newFlushHook().run(Duration.ofSeconds(2));
            }));
```

Applications with an existing lifecycle manager should perform the same sequence there instead of registering another JVM hook. `Builder.setFlushOnWorkerFactoryShutdown(true)` restores the underlying plugin's immediate, best-effort flush, but it should only be used when no work can emit telemetry after the shutdown request.

The OTLP endpoint is resolved in this order:

1. `Builder.setEndpoint(...)`.
2. `OTEL_EXPORTER_OTLP_ENDPOINT`.
3. `http://localhost:4317`.

The OpenTelemetry service name is resolved in this order:

1. `Builder.setServiceName(...)`.
2. `OTEL_SERVICE_NAME`.
3. `CLOUD_RUN_WORKER_POOL` for a Cloud Run worker pool.
4. `K_SERVICE` for a Cloud Run service.
5. `temporal-worker`.

The collector should use its GCP resource detector to add the Google Cloud attributes it recognizes. Do not rely on the detector to infer Cloud Run worker-pool-specific location or revision attributes; configure those explicitly with a collector resource processor if they are required. This module does not call the Google Cloud metadata server and adds no Google Cloud client libraries or exporters to the worker process.

## Collector sidecar

Google publishes the Google-Built OpenTelemetry Collector as a container image. Configure it as a second Cloud Run container, listen for OTLP gRPC on `localhost:4317`, and use its GCP exporters for metrics and traces. For the image, recommended collector configuration, IAM roles, health check, and Secret Manager mount, see [Deploy Google-Built OpenTelemetry Collector on Cloud Run](https://cloud.google.com/stackdriver/docs/instrumentation/opentelemetry-collector-cloud-run). That guide demonstrates a Cloud Run service; adapt its collector container and configuration when deploying a worker pool.

Cloud Run worker pools support sidecar containers over localhost and are intended for continuous background work. The deployment should start the collector before the Temporal worker and use the collector health extension as its startup probe.

To use an external collector instead, set `OTEL_EXPORTER_OTLP_ENDPOINT` or call `Builder.setEndpoint(...)`.

To use an application-owned provider, call `Builder.setOpenTelemetry(...)`. In that path, no exporters are created; the plugin installs the Temporal metrics scope, tracing interceptors, and shutdown flush hook around the supplied provider.
