/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.common.reporter;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AtomicDouble;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.CapableOf;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.util.Duration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MicrometerClientStatsReporter implements StatsReporter {

  private final MeterRegistry registry;

  /**
   * This retains all created gauges to keep them publishing. According to Micrometer contract, it's
   * a user code <a
   * href="https://micrometer.io/docs/concepts#_why_is_my_gauge_reporting_nan_or_disappearing">
   * "responsibility to hold a strong reference to the state object that you are measuring with a
   * Gauge" </a>.
   *
   * <p>The need to forcefully retain all the gauges is coming from misalignment of Tally's (that
   * doesn't expose any way to retain or not retain a gauge) and Micrometer/Prometheus Gauge API.
   *
   * <p>This theoretically may cause a memory leak if users create gauges with names or tags of very
   * large cardinalities or if the names of gauges or tags have unique values.
   *
   * <p>The design to retain gauges is based on <a
   * href="https://github.com/uber-java/tally/blob/master/prometheus/src/main/java/com/uber/m3/tally/experimental/prometheus/PrometheusReporter.java">Tally's
   * PrometheusReporter<a/>
   */
  private final ConcurrentMap<MetricID, AtomicDouble> registeredGauges = new ConcurrentHashMap<>();

  public MicrometerClientStatsReporter(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry);
  }

  @Override
  public Capabilities capabilities() {
    return CapableOf.REPORTING;
  }

  @Override
  public void flush() {
    // NOOP
  }

  @Override
  public void close() {
    registry.close();
  }

  @Override
  public void reportCounter(String name, Map<String, String> tags, long value) {
    registry.counter(name, getTags(tags)).increment(value);
  }

  @Override
  public void reportGauge(String name, Map<String, String> tags, double value) {
    Preconditions.checkNotNull(name, "Gauge name should be not null");
    MetricID metricID = new MetricID(name, tags);
    AtomicDouble gauge =
        registeredGauges.computeIfAbsent(
            metricID,
            _metricID ->
                Objects.requireNonNull(
                    registry.gauge(
                        _metricID.name, getTags(_metricID.tags), new AtomicDouble(value))));
    gauge.set(value);
  }

  @Override
  public void reportTimer(String name, Map<String, String> tags, Duration interval) {
    Timer.builder(name)
        .tags(getTags(tags))
        .publishPercentileHistogram(true)
        .register(registry)
        .record(interval.getNanos(), TimeUnit.NANOSECONDS);
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
    // NOOP
  }

  @Override
  @SuppressWarnings("deprecation")
  public void reportHistogramDurationSamples(
      String name,
      Map<String, String> tags,
      com.uber.m3.tally.Buckets buckets,
      Duration bucketLowerBound,
      Duration bucketUpperBound,
      long samples) {
    // NOOP
  }

  private static class MetricID {
    private final String name;
    private final Map<String, String> tags;

    private MetricID(String name, Map<String, String> tags) {
      this.name = name;
      this.tags = tags;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      MetricID metricID = (MetricID) o;
      return name.equals(metricID.name) && Objects.equals(tags, metricID.tags);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, tags);
    }
  }

  private Iterable<Tag> getTags(Map<String, String> tags) {
    return tags != null
        ? tags.entrySet().stream()
            .map(entry -> Tag.of(entry.getKey(), entry.getValue()))
            .collect(Collectors.toList())
        : Collections.emptyList();
  }
}
