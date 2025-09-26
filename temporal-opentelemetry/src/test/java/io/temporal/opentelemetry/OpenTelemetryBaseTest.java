package io.temporal.opentelemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.After;

/** Base class for OpenTelemetry tests that provides common setup and utilities. */
public abstract class OpenTelemetryBaseTest {

  // Create span exporter for test verification
  protected final InMemorySpanExporter spanExporter = InMemorySpanExporter.create();

  // Setup OpenTelemetry resources
  protected final Resource resource =
      Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "temporal-test"));

  // Setup tracer provider
  protected final SdkTracerProvider tracerProvider =
      SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
          .setResource(resource)
          .build();

  // Create the OpenTelemetry instance first before using it in options
  protected final OpenTelemetrySdk openTelemetry =
      OpenTelemetrySdk.builder()
          .setTracerProvider(tracerProvider)
          // Add baggage propagator to ensure baggage is propagated properly
          .setPropagators(
              ContextPropagators.create(
                  io.opentelemetry.context.propagation.TextMapPropagator.composite(
                      W3CTraceContextPropagator.getInstance(),
                      io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator.getInstance())))
          .build();

  // Create the OpenTelemetry options with a safer span builder provider
  protected final OpenTelemetryOptions openTelemetryOptions =
      OpenTelemetryOptions.newBuilder()
          .setOpenTelemetry(openTelemetry)
          .setTracer(openTelemetry.getTracer("io.temporal.test"))
          .setSpanBuilderProvider(new TestOpenTelemetrySpanBuilderProvider())
          .build();

  @After
  public void tearDown() {
    spanExporter.reset();
  }
}
