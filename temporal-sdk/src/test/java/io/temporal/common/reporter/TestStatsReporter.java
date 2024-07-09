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

import static java.util.stream.Collectors.joining;
import static org.junit.Assert.*;

import com.google.common.math.StatsAccumulator;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.StatsReporter;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class TestStatsReporter implements StatsReporter {

  private final Map<String, AtomicLong> counters = new HashMap<>();
  private final Map<String, Double> gauges = new HashMap<>();
  private final Map<String, StatsAccumulator> timers = new HashMap<>();

  public synchronized void assertCounter(String name, Map<String, String> tags) {
    String metricName = getMetricName(name, tags);
    if (!counters.containsKey(metricName)) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", counters.keySet()));
    }
  }

  public synchronized void assertNoMetric(String name, Map<String, String> tags) {
    String metricName = getMetricName(name, tags);
    if (counters.containsKey(metricName)) {
      fail(
          "Metric '"
              + metricName
              + "' was reported, with value: '"
              + counters.get(metricName).get()
              + "'");
    }
  }

  public synchronized void assertCounter(String name, Map<String, String> tags, long expected) {
    assertCounter(name, tags, actual -> actual == expected);
  }

  public synchronized void assertCounter(
      String name, Map<String, String> tags, Predicate<Long> expected) {
    String metricName = getMetricName(name, tags);
    AtomicLong accumulator = counters.get(metricName);
    if (accumulator == null) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", counters.keySet()));
    }
    long actual = accumulator.get();
    assertTrue("" + actual, expected.test(actual));
  }

  public synchronized void assertGauge(String name, Map<String, String> tags, double expected) {
    assertGauge(name, tags, val -> Math.abs(expected - val) < 1e-3);
  }

  public synchronized void assertGauge(
      String name, Map<String, String> tags, Predicate<Double> isExpected) {
    String metricName = getMetricName(name, tags);
    Double value = gauges.get(metricName);
    if (value == null) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", gauges.keySet()));
    }
    assertTrue(String.valueOf(value), isExpected.test(value));
  }

  public synchronized void assertTimer(String name, Map<String, String> tags) {
    String metricName = getMetricName(name, tags);
    if (!timers.containsKey(metricName)) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", timers.keySet()));
    }
  }

  public synchronized void assertTimerMinDuration(
      String name, Map<String, String> tags, Duration minDuration) {
    String metricName = getMetricName(name, tags);
    StatsAccumulator value = timers.get(metricName);
    if (value == null) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", timers.keySet()));
    }
    assertTrue(
        "Timer " + metricName + " is more than " + minDuration,
        value.min() >= minDuration.toMillis());
  }

  @Override
  public synchronized void reportCounter(String name, Map<String, String> tags, long value) {
    String metricName = getMetricName(name, tags);
    AtomicLong accumulator = counters.get(metricName);
    if (accumulator == null) {
      accumulator = new AtomicLong();
      counters.put(metricName, accumulator);
    }
    accumulator.addAndGet(value);
  }

  @Override
  public synchronized void reportGauge(String name, Map<String, String> tags, double value) {
    String metricName = getMetricName(name, tags);
    gauges.put(metricName, value);
  }

  @Override
  public synchronized void reportTimer(
      String name, Map<String, String> tags, com.uber.m3.util.Duration interval) {
    String metricName = getMetricName(name, tags);
    StatsAccumulator value = timers.get(metricName);
    if (value == null) {
      value = new StatsAccumulator();
      timers.put(metricName, value);
    }
    value.add(interval.toMillis());
  }

  @SuppressWarnings("deprecation")
  @Override
  public synchronized void reportHistogramValueSamples(
      String name,
      Map<String, String> tags,
      com.uber.m3.tally.Buckets buckets,
      double bucketLowerBound,
      double bucketUpperBound,
      long samples) {
    throw new UnsupportedOperationException();
  }

  @SuppressWarnings("deprecation")
  @Override
  public synchronized void reportHistogramDurationSamples(
      String name,
      Map<String, String> tags,
      com.uber.m3.tally.Buckets buckets,
      com.uber.m3.util.Duration bucketLowerBound,
      com.uber.m3.util.Duration bucketUpperBound,
      long samples) {
    throw new UnsupportedOperationException();
  }

  @Override
  public synchronized Capabilities capabilities() {
    throw new UnsupportedOperationException();
  }

  @Override
  public synchronized void flush() {}

  @Override
  public synchronized void close() {}

  private String getMetricName(String name, Map<String, String> tags) {
    return name
        + " "
        + tags.entrySet().stream()
            .map(Map.Entry::toString)
            .sorted()
            .collect(joining("|", "[", "]"));
  }
}
