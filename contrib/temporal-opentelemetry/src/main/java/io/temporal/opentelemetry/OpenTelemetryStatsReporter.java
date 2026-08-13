package io.temporal.opentelemetry;

import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.CapableOf;
import com.uber.m3.tally.StatsReporter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleGauge;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;

/** Tally reporter that emits Temporal metrics through OpenTelemetry. */
public final class OpenTelemetryStatsReporter implements StatsReporter {
  private final Meter meter;
  private final String serviceName;
  private final ConcurrentMap<String, LongCounter> counters = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, DoubleHistogram> timers = new ConcurrentHashMap<>();
  private final ConcurrentMap<MetricKey, GaugeHolder> gauges = new ConcurrentHashMap<>();

  public OpenTelemetryStatsReporter(
      @Nonnull OpenTelemetry openTelemetry, @Nonnull String serviceName) {
    this.meter = Objects.requireNonNull(openTelemetry, "openTelemetry").getMeter("io.temporal");
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
  }

  @Override
  public Capabilities capabilities() {
    return CapableOf.REPORTING;
  }

  @Override
  public void flush() {
    // OpenTelemetry SDK flushing is handled by OpenTelemetryWorker's shutdown hook.
  }

  @Override
  public void close() {
    flush();
  }

  @Override
  public void reportCounter(String name, Map<String, String> tags, long value) {
    LongCounter counter = counters.computeIfAbsent(name, key -> meter.counterBuilder(key).build());
    counter.add(value, attributes(tags));
  }

  @Override
  public void reportGauge(String name, Map<String, String> tags, double value) {
    MetricKey key = new MetricKey(name, tags);
    GaugeHolder holder =
        gauges.computeIfAbsent(
            key,
            metricKey -> {
              AtomicReference<Double> current = new AtomicReference<>(0.0);
              ObservableDoubleGauge gauge =
                  meter
                      .gaugeBuilder(metricKey.name)
                      .buildWithCallback(
                          measurement ->
                              measurement.record(current.get(), attributes(metricKey.tags)));
              return new GaugeHolder(current, gauge);
            });
    holder.current.set(value);
  }

  @Override
  public void reportTimer(
      String name, Map<String, String> tags, com.uber.m3.util.Duration interval) {
    DoubleHistogram timer =
        timers.computeIfAbsent(name, key -> meter.histogramBuilder(key).setUnit("ms").build());
    timer.record(interval.getNanos() / 1_000_000.0, attributes(tags));
  }

  @Override
  @SuppressWarnings("deprecation")
  public void reportHistogramValueSamples(
      String name,
      Map<String, String> tags,
      com.uber.m3.tally.Buckets buckets,
      double bucketLowerBound,
      double bucketUpperBound,
      long samples) {
    // Tally reports pre-aggregated bucket samples, while the OpenTelemetry API records raw values.
  }

  @Override
  @SuppressWarnings("deprecation")
  public void reportHistogramDurationSamples(
      String name,
      Map<String, String> tags,
      com.uber.m3.tally.Buckets buckets,
      com.uber.m3.util.Duration bucketLowerBound,
      com.uber.m3.util.Duration bucketUpperBound,
      long samples) {
    // Tally reports pre-aggregated bucket samples, while the OpenTelemetry API records raw values.
  }

  private Attributes attributes(Map<String, String> tags) {
    AttributesBuilder builder = Attributes.builder();
    builder.put("service.name", serviceName);
    if (tags != null) {
      for (Map.Entry<String, String> entry : tags.entrySet()) {
        if (entry.getKey() != null && entry.getValue() != null) {
          builder.put(entry.getKey(), entry.getValue());
        }
      }
    }
    return builder.build();
  }

  private static final class GaugeHolder {
    private final AtomicReference<Double> current;

    @SuppressWarnings("unused")
    private final ObservableDoubleGauge gauge;

    private GaugeHolder(AtomicReference<Double> current, ObservableDoubleGauge gauge) {
      this.current = current;
      this.gauge = gauge;
    }
  }

  private static final class MetricKey {
    private final String name;
    private final Map<String, String> tags;

    private MetricKey(String name, Map<String, String> tags) {
      this.name = Objects.requireNonNull(name, "name");
      this.tags =
          tags == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(tags));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      MetricKey metricKey = (MetricKey) o;
      return name.equals(metricKey.name) && tags.equals(metricKey.tags);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, tags);
    }
  }
}
