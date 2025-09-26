package io.temporal.opentelemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OpenTelemetrySpansHelper {

  private final InMemorySpanExporter spanExporter;
  private Map<String, SpanData> spanIdToSpan;

  public OpenTelemetrySpansHelper(InMemorySpanExporter spanExporter) {
    this.spanExporter = spanExporter;
  }

  // Get all recorded spans without side effects
  public List<SpanData> getRecordedSpans() {
    return spanExporter.getFinishedSpanItems();
  }

  // Initialize the internal maps for lookups - returns the same spans for convenience
  public List<SpanData> initializeSpanLookups() {
    List<SpanData> spans = getRecordedSpans();

    // Initialize the map for faster lookups
    this.spanIdToSpan = new HashMap<>();
    spans.forEach(span -> spanIdToSpan.put(span.getSpanId(), span));

    return spans;
  }

  // Get a span by its span ID
  public SpanData getSpan(String spanId) {
    if (spanIdToSpan == null) {
      initializeSpanLookups(); // Initialize the map if needed
    }
    return spanIdToSpan.get(spanId);
  }

  // Get a span by name - similar to getSpanByOperationName in OpenTracingSpansHelper
  public SpanData getSpanByName(String name) {
    return getSpansWithName(name).stream().findFirst().orElse(null);
  }

  public List<SpanData> getSpansWithName(String name) {
    // Initialize lookup maps if needed
    if (spanIdToSpan == null) {
      initializeSpanLookups();
    }

    return getRecordedSpans().stream()
        .filter(span -> span.getName().equals(name))
        .collect(Collectors.toList());
  }

  // Get spans with a specific string attribute value
  public List<SpanData> getSpansWithAttributeValue(AttributeKey<String> key, String value) {
    if (spanIdToSpan == null) {
      initializeSpanLookups();
    }
    return getRecordedSpans().stream()
        .filter(span -> value.equals(span.getAttributes().get(key)))
        .collect(Collectors.toList());
  }

  // Get spans with a specific boolean attribute value
  public List<SpanData> getSpansWithAttributeValue(AttributeKey<Boolean> key, boolean value) {
    if (spanIdToSpan == null) {
      initializeSpanLookups();
    }
    return getRecordedSpans().stream()
        .filter(span -> value == Boolean.TRUE.equals(span.getAttributes().get(key)))
        .collect(Collectors.toList());
  }

  // Get spans with a specific span kind
  public List<SpanData> getSpansWithKind(SpanKind kind) {
    if (spanIdToSpan == null) {
      initializeSpanLookups();
    }
    return getRecordedSpans().stream()
        .filter(span -> span.getKind() == kind)
        .collect(Collectors.toList());
  }

  // Get spans with a specific status code
  public List<SpanData> getSpansWithStatus(StatusCode status) {
    if (spanIdToSpan == null) {
      initializeSpanLookups();
    }
    return getRecordedSpans().stream()
        .filter(span -> span.getStatus().getStatusCode() == status)
        .collect(Collectors.toList());
  }

  // Get a span by trace ID
  public SpanData getSpanByTraceId(String traceId) {
    if (spanIdToSpan == null) {
      initializeSpanLookups();
    }
    return getRecordedSpans().stream()
        .filter(span -> span.getTraceId().equals(traceId))
        .findFirst()
        .orElse(null);
  }

  // Get child spans - similar to getByParentSpan in OpenTracingSpansHelper
  public List<SpanData> getChildSpans(SpanData parentSpan) {
    return getByParentSpan(parentSpan);
  }

  // Match the method name from OpenTracingSpansHelper
  public List<SpanData> getByParentSpan(SpanData parentSpan) {
    if (spanIdToSpan == null) {
      initializeSpanLookups();
    }
    String parentSpanId = parentSpan.getSpanId();
    return getRecordedSpans().stream()
        .filter(span -> span.getParentSpanId().equals(parentSpanId))
        .collect(Collectors.toList());
  }
}
