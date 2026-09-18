# OpenTelemetry v2 integration for the Temporal Java SDK

Module `io.temporal:temporal-opentelemetry-v2` provides replay-safe
OpenTelemetry tracing, metrics, and logs for Temporal.

## Setup

Use the same version as the rest of your Temporal Java SDK dependencies:

```groovy
implementation 'io.temporal:temporal-opentelemetry-v2:<temporal-java-sdk-version>'
// Add the exporters you use, for example:
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
```

Create a replay-safe OpenTelemetry instance, register it as the global, and
attach the plugin to your service stubs:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.temporal.client.WorkflowClient;
import io.temporal.opentelemetry.v2.OpenTelemetryPlugin;
import io.temporal.opentelemetry.v2.ReplaySafeOpenTelemetry;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;

ReplaySafeOpenTelemetry openTelemetry =
    ReplaySafeOpenTelemetry.newBuilder()
        .setTracerProviderBuilder(
            SdkTracerProvider.builder()
                .addSpanProcessor(
                    BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder().build()).build()))
        .build();
GlobalOpenTelemetry.set(openTelemetry);

WorkflowServiceStubs service =
    WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder()
            .setPlugins(OpenTelemetryPlugin.newBuilder().build())
            .build());

WorkflowClient client = WorkflowClient.newInstance(service);
WorkerFactory factory = WorkerFactory.newInstance(client);
```

Plugins configured on `WorkflowServiceStubsOptions` propagate to clients and
workers created from those stubs. It can also be configured directly on
`WorkflowClientOptions` or `WorkerFactoryOptions`.

## Tracing

The plugin propagates application trace context through Temporal headers.
Application spans remain connected across clients, workflows, activities, and
Nexus operations.

Set `OpenTelemetryPlugin.Builder.setAddTemporalSpans(true)` to emit spans for
operations such as `StartWorkflow`, `RunWorkflow`, `RunActivity`, and
`ContinueAsNew`.

Create spans in workflow, client, and activity code with the standard OpenTelemetry API:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

Span span =
    GlobalOpenTelemetry.getTracer("my-workflows")
        .spanBuilder("my-span")
        .startSpan();
try (Scope ignored = span.makeCurrent()) {
  activity.doWork();
} finally {
  span.end();
}
```

## Metrics

Create synchronous metrics in workflow, client, and activity code with the standard OpenTelemetry API:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;

LongCounter counter =
    GlobalOpenTelemetry.getMeter("my-workflows")
        .counterBuilder("workflow.items.processed")
        .build();
counter.add(1);
```

## Logs

Create log records in workflow, client, and activity code with the standard OpenTelemetry API:

```java
import io.opentelemetry.api.GlobalOpenTelemetry;

GlobalOpenTelemetry.get()
    .getLogsBridge()
    .get("my-workflows")
    .logRecordBuilder()
    .setBody("workflow step completed")
    .emit();
```
