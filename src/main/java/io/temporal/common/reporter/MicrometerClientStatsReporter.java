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

import com.uber.m3.tally.Buckets;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.CapableOf;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.util.Duration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class MicrometerClientStatsReporter implements StatsReporter {

  private final MeterRegistry registry;

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
    registry.gauge(name, getTags(tags), value);
  }

  @Override
  public void reportTimer(String name, Map<String, String> tags, Duration interval) {
    registry.timer(name, getTags(tags)).record(interval.getNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public void reportHistogramValueSamples(
      String name,
      Map<String, String> tags,
      Buckets buckets,
      double bucketLowerBound,
      double bucketUpperBound,
      long samples) {
    // NOOP
  }

  @Override
  public void reportHistogramDurationSamples(
      String name,
      Map<String, String> tags,
      Buckets buckets,
      Duration bucketLowerBound,
      Duration bucketUpperBound,
      long samples) {
    // NOOP
  }

  private Iterable<Tag> getTags(Map<String, String> tags) {
    return tags.entrySet().stream()
        .map(entry -> Tag.of(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());
  }
}
