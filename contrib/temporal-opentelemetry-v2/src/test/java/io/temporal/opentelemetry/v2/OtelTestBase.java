package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

public abstract class OtelTestBase {
  static final InMemorySpanExporter spanExporter = InMemorySpanExporter.create();
  static final InMemoryMetricReader metricReader = InMemoryMetricReader.create();
  static final InMemoryLogRecordExporter logExporter = InMemoryLogRecordExporter.create();
  private static ReplaySafeOpenTelemetry openTelemetry;

  @BeforeClass
  public static void registerGlobalOpenTelemetry() {
    openTelemetry =
        ReplaySafeOpenTelemetry.newBuilder()
            .setTracerProviderBuilder(
                SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter)))
            .setMeterProviderBuilder(SdkMeterProvider.builder().registerMetricReader(metricReader))
            .setLoggerProviderBuilder(
                SdkLoggerProvider.builder()
                    .addLogRecordProcessor(SimpleLogRecordProcessor.create(logExporter)))
            .build();
    GlobalOpenTelemetry.set(openTelemetry);
  }

  @AfterClass
  public static void resetGlobalOpenTelemetry() {
    GlobalOpenTelemetry.resetForTest();
    openTelemetry.close();
  }

  @Before
  public void clearSpansAndLogs() {
    spanExporter.reset();
    logExporter.reset();
  }

  /**
   * A rule whose service stubs carry the plugin, so it propagates to every client and the worker,
   * and whose workers keep no sticky cache so replay runs on every task.
   */
  static SDKTestWorkflowRule.Builder newRuleBuilder(boolean addTemporalSpans) {
    return SDKTestWorkflowRule.newBuilder()
        .setWorkflowServiceStubsOptions(
            WorkflowServiceStubsOptions.newBuilder()
                .setPlugins(
                    OpenTelemetryPlugin.newBuilder().setAddTemporalSpans(addTemporalSpans).build())
                .build())
        .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder().setWorkflowCacheSize(0).build());
  }

  static List<SpanData> endedSpans() {
    return spanExporter.getFinishedSpanItems();
  }

  static List<LogRecordData> emittedLogs() {
    return logExporter.getFinishedLogRecordItems();
  }

  static SpanData requireSpanNamed(List<SpanData> spans, String name) {
    for (SpanData span : spans) {
      if (span.getName().equals(name)) {
        return span;
      }
    }
    fail(name + " span not found in " + spanTree(spans));
    return null;
  }

  static MetricData requireMetricNamed(String name) {
    for (MetricData metric : metricReader.collectAllMetrics()) {
      if (metric.getName().equals(name)) {
        return metric;
      }
    }
    fail(name + " metric not found");
    return null;
  }

  static String requireSpanAttribute(SpanData span, AttributeKey<String> key) {
    String value = span.getAttributes().get(key);
    assertNotNull(key.getKey() + " attribute not found on " + span.getName(), value);
    return value;
  }

  static void requireUniqueSpanIds(List<SpanData> spans) {
    Map<String, String> namesById = new HashMap<>();
    for (SpanData span : spans) {
      String previous = namesById.put(span.getSpanId(), span.getName());
      if (previous != null) {
        fail("span " + span.getName() + " shares an ID with span " + previous);
      }
    }
  }

  static void assertSpanTree(List<String> expected, List<SpanData> spans) {
    assertEquals(String.join("\n", expected), String.join("\n", spanTree(spans)));
  }

  /** Returns the spans as an indented tree in end order. */
  static List<String> spanTree(List<SpanData> spans) {
    Map<Integer, List<Integer>> childrenByParent = new HashMap<>();
    for (int child = 0; child < spans.size(); child++) {
      childrenByParent
          .computeIfAbsent(closestParentIndex(spans, spans.get(child)), k -> new ArrayList<>())
          .add(child);
    }
    List<String> tree = new ArrayList<>();
    appendChildren(spans, childrenByParent, -1, 0, tree);
    return tree;
  }

  /**
   * Resets can emit the same span ID and start time more than once; the nearest end time identifies
   * the matching parent.
   */
  private static int closestParentIndex(List<SpanData> spans, SpanData child) {
    String parentId = child.getParentSpanContext().getSpanId();
    int closest = -1;
    long closestDistance = Long.MAX_VALUE;
    for (int i = 0; i < spans.size(); i++) {
      if (!spans.get(i).getSpanId().equals(parentId)) {
        continue;
      }
      long distance = Math.abs(child.getEndEpochNanos() - spans.get(i).getEndEpochNanos());
      if (distance < closestDistance) {
        closest = i;
        closestDistance = distance;
      }
    }
    return closest;
  }

  private static void appendChildren(
      List<SpanData> spans,
      Map<Integer, List<Integer>> childrenByParent,
      int parent,
      int depth,
      List<String> tree) {
    for (int child : childrenByParent.getOrDefault(parent, new ArrayList<>())) {
      tree.add(repeat("  ", depth) + spans.get(child).getName());
      appendChildren(spans, childrenByParent, child, depth + 1, tree);
    }
  }

  private static String repeat(String s, int times) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < times; i++) {
      out.append(s);
    }
    return out.toString();
  }
}
