/*
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

package com.uber.cadence.internal.metrics;

import com.uber.m3.tally.Buckets;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.CapableOf;
import com.uber.m3.tally.Counter;
import com.uber.m3.tally.Gauge;
import com.uber.m3.tally.Histogram;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.tally.Timer;
import com.uber.m3.util.Duration;
import java.util.Map;

public final class NoopScope implements Scope {
  private static Scope noopScope;
  private static Counter noopCounter;
  private static Gauge noopGauge;
  private static Timer noopTimer;
  private static Histogram noopHistogram;

  @Override
  public Counter counter(String name) {
    return noopCounter;
  }

  @Override
  public Gauge gauge(String name) {
    return noopGauge;
  }

  @Override
  public Timer timer(String name) {
    return noopTimer;
  }

  @Override
  public Histogram histogram(String name, Buckets buckets) {
    return noopHistogram;
  }

  @Override
  public Scope tagged(Map<String, String> tags) {
    return this;
  }

  @Override
  public Scope subScope(String name) {
    return this;
  }

  @Override
  public Capabilities capabilities() {
    return CapableOf.NONE;
  }

  @Override
  public void close() {}

  private NoopScope() {}

  public static synchronized Scope getInstance() {
    if (noopScope == null) {
      noopCounter = delta -> {};
      noopGauge = value -> {};
      noopTimer =
          new Timer() {
            @Override
            public void record(Duration interval) {}

            @Override
            public Stopwatch start() {
              return new Stopwatch(0, stopwatchStart -> {});
            }
          };
      noopHistogram =
          new Histogram() {
            @Override
            public void recordValue(double value) {}

            @Override
            public void recordDuration(Duration value) {}

            @Override
            public Stopwatch start() {
              return new Stopwatch(0, stopwatchStart -> {});
            }
          };

      noopScope = new NoopScope();
    }
    return noopScope;
  }
}
