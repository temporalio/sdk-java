/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.common.reporter;

import static java.util.stream.Collectors.joining;
import static org.junit.Assert.*;

import com.google.common.math.StatsAccumulator;
import com.uber.m3.tally.Buckets;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.StatsReporter;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class TestStatsReporter implements StatsReporter {

  private final Map<String, AtomicLong> counters = new HashMap<>();
  private final Map<String, Double> gauges = new HashMap<>();
  private final Map<String, StatsAccumulator> timers = new HashMap<>();

  public void assertCounter(String name, Map<String, String> tags) {
    String metricName = getMetricName(name, tags);
    if (!counters.containsKey(metricName)) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", counters.keySet()));
    }
  }

  public void assertNoMetric(String name, Map<String, String> tags) {
    String metricName = getMetricName(name, tags);
    if (counters.containsKey(metricName)) {
      fail(
          "Metric '"
              + metricName
              + "', all reported metrics: \n "
              + String.join("\n ", counters.keySet()));
    }
  }

  public void assertCounter(String name, Map<String, String> tags, long expected) {
    String metricName = getMetricName(name, tags);
    AtomicLong accumulator = counters.get(metricName);
    if (accumulator == null) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", counters.keySet()));
    }
    assertEquals(String.valueOf(accumulator.get()), expected, accumulator.get());
  }

  public void assertGauge(String name, Map<String, String> tags, double expected) {
    String metricName = getMetricName(name, tags);
    Double value = gauges.get(metricName);
    if (value == null) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", gauges.keySet()));
    }
    assertEquals(String.valueOf(value), expected, value, 1e-3);
  }

  public void assertTimer(String name, Map<String, String> tags) {
    String metricName = getMetricName(name, tags);
    if (!timers.containsKey(metricName)) {
      fail(
          "No metric '"
              + metricName
              + "', reported metrics: \n "
              + String.join("\n ", timers.keySet()));
    }
  }

  public void assertTimerMinDuration(String name, Map<String, String> tags, Duration minDuration) {
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
  public void reportTimer(
      String name, Map<String, String> tags, com.uber.m3.util.Duration interval) {
    String metricName = getMetricName(name, tags);
    StatsAccumulator value = timers.get(metricName);
    if (value == null) {
      value = new StatsAccumulator();
      timers.put(metricName, value);
    }
    value.add(interval.toMillis());
  }

  @Override
  public void reportHistogramValueSamples(
      String name,
      Map<String, String> tags,
      Buckets buckets,
      double bucketLowerBound,
      double bucketUpperBound,
      long samples) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void reportHistogramDurationSamples(
      String name,
      Map<String, String> tags,
      Buckets buckets,
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
