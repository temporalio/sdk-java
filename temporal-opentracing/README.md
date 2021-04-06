# Temporal [OpenTracing](https://opentracing.io/) support module

This module provides a set of Interceptors that adds support for OpenTracing Span Context propagation to Temporal.

## Usage

You want to register two interceptors - one on the Temporal client side, another on the worker side:

1. Client configuration:
    ```java
    WorkflowClientOptions.newBuilder()
       //...
       .setInterceptors(new OpenTracingClientInterceptor())
       .build();
    ```
2. Worker configuration:
    ```java
    WorkerFactoryOptions.newBuilder()
       //...
       .setWorkerInterceptors(new OpenTracingWorkerInterceptor())
       .build();
    ```

## [OpenTelemetry](https://opentelemetry.io/)

OpenTracing has been merged into OpenTelemetry and nowadays OpenTelemetry should be a preferred solution.
There is still plenty of OpenTracing usage everywhere and there is an official OpenTracing -> OpenTelemetry bridge,
but no OpenTelemetry -> OpenTracing bridges.

To give the best coverage in the simplest way, this module is implemented based on OpenTracing for now.
OpenTelemetry users are advised to use the 
[OpenTracing -> OpenTelemetry bridge](https://github.com/open-telemetry/opentelemetry-java/tree/main/opentracing-shim) 
to hook their OpenTelemetry setup and make it available for OpenTracing API:

```java
    io.opentracing.Tracer tracer = OpenTracingShim.createTracerShim();
    //or io.opentracing.Tracer tracer = OpenTracingShim.createTracerShim(openTelemetry);
    GlobalTracer.registerIfAbsent(tracer);
```


