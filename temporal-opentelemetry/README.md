# Temporal [OpenTelemetry](https://opentelemetry.io/) module

This module provides a set of Interceptors that adds support for OpenTelemetry tracing to Temporal.

## Usage

You want to register two interceptors - one on the Temporal client side, another on the worker side:

1. Client configuration:
    ```java
    WorkflowClientOptions.newBuilder()
       //...
       .setInterceptors(new OpenTelemetryClientInterceptor())
       .build();
    ```
2. Worker configuration:
    ```java
    WorkerFactoryOptions.newBuilder()
       //...
       .setWorkerInterceptors(new OpenTelemetryWorkerInterceptor())
       .build();
    ```

## OpenTelemetry Configuration

By default, this module uses the global OpenTelemetry instance. You can configure it as follows:

```java
// Configure the OpenTelemetry SDK
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .addSpanProcessor(BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder().build()).build())
    .setResource(Resource.getDefault().toBuilder()
        .put(ResourceAttributes.SERVICE_NAME, "my-temporal-service")
        .build())
    .build();

OpenTelemetry openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    // Configure propagators to support both trace context AND baggage
    .setPropagators(ContextPropagators.create(
        TextMapPropagator.composite(
            W3CTraceContextPropagator.getInstance(),
            W3CBaggagePropagator.getInstance()
        )
    ))
    .build();

// Register with Temporal
OpenTelemetryOptions options = OpenTelemetryOptions.newBuilder()
    .setOpenTelemetry(openTelemetry)
    .build();

// Use with interceptors
new OpenTelemetryClientInterceptor(options);
new OpenTelemetryWorkerInterceptor(options);
```

## Baggage Support

To ensure both trace context and baggage are properly propagated throughout your workflows, make sure to configure OpenTelemetry with both the W3CTraceContextPropagator and W3CBaggagePropagator:

```java
// Configure both trace context and baggage propagation
.setPropagators(ContextPropagators.create(
    TextMapPropagator.composite(
        W3CTraceContextPropagator.getInstance(),
        W3CBaggagePropagator.getInstance()
    )
))
```

This configuration ensures that both trace IDs and baggage items are properly propagated between services and across workflow boundaries.

## Comparison with OpenTracing

This module is a native OpenTelemetry implementation, providing direct integration with OpenTelemetry without the need for adapters or bridges. It offers:

1. Full W3C TraceContext compatibility
2. Support for all OpenTelemetry features (metrics, logs, baggage)
3. Integration with the broader OpenTelemetry ecosystem
4. Proper baggage propagation across workflow boundaries

If you're currently using the `temporal-opentracing` module with the OpenTelemetry bridge, consider migrating to this module for better compatibility and performance.