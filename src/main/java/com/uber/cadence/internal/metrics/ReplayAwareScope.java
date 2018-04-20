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

import com.uber.cadence.internal.replay.ReplayAware;
import com.uber.m3.tally.Buckets;
import com.uber.m3.tally.Capabilities;
import com.uber.m3.tally.Counter;
import com.uber.m3.tally.Gauge;
import com.uber.m3.tally.Histogram;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.ScopeCloseException;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.tally.Timer;
import com.uber.m3.util.Duration;
import java.util.Map;
import java.util.Objects;

public class ReplayAwareScope implements Scope {
  Scope scope;
  ReplayAware context;

  public ReplayAwareScope(Scope scope, ReplayAware context) {
    this.scope = Objects.requireNonNull(scope);
    this.context = Objects.requireNonNull(context);
  }

  private static class ReplayAwareCounter implements Counter {
    Counter counter;
    ReplayAware context;

    public ReplayAwareCounter(Counter counter, ReplayAware context) {
      this.counter = Objects.requireNonNull(counter);
      this.context = Objects.requireNonNull(context);
    }

    @Override
    public void inc(long delta) {
      if (context.isReplaying()) {
        return;
      }

      counter.inc(delta);
    }
  }

  private static class ReplayAwareGauge implements Gauge {
    Gauge gauge;
    ReplayAware context;

    public ReplayAwareGauge(Gauge gauge, ReplayAware context) {
      this.gauge = Objects.requireNonNull(gauge);
      this.context = Objects.requireNonNull(context);
    }

    @Override
    public void update(double value) {
      if (context.isReplaying()) {
        return;
      }

      gauge.update(value);
    }
  }

  private static class ReplayAwareTimer implements Timer {
    Timer timer;
    ReplayAware context;

    public ReplayAwareTimer(Timer timer, ReplayAware context) {
      this.timer = Objects.requireNonNull(timer);
      this.context = Objects.requireNonNull(context);
    }

    @Override
    public void record(Duration interval) {
      if (context.isReplaying()) {
        return;
      }

      timer.record(interval);
    }

    @Override
    public Stopwatch start() {
      // TODO: Stopwatch is a concrete class with non-public constructor so there's no
      // way to extend it. Need to fix this when the following issue is resolved.
      // https://github.com/uber-java/tally/issues/26
      return timer.start();
    }
  }

  private static class ReplayAwareHistogram implements Histogram {
    Histogram histogram;
    ReplayAware context;

    public ReplayAwareHistogram(Histogram histogram, ReplayAware context) {
      this.histogram = Objects.requireNonNull(histogram);
      this.context = Objects.requireNonNull(context);
    }

    @Override
    public void recordValue(double value) {
      if (context.isReplaying()) {
        return;
      }

      histogram.recordValue(value);
    }

    @Override
    public void recordDuration(Duration value) {
      if (context.isReplaying()) {
        return;
      }

      histogram.recordDuration(value);
    }

    @Override
    public Stopwatch start() {
      // TODO: Stopwatch is a concrete class with non-public constructor so there's no
      // way to extend it. Need to fix this when the following issue is resolved.
      // https://github.com/uber-java/tally/issues/26
      return histogram.start();
    }
  }

  @Override
  public Counter counter(String name) {
    return new ReplayAwareCounter(scope.counter(name), context);
  }

  @Override
  public Gauge gauge(String name) {
    return new ReplayAwareGauge(scope.gauge(name), context);
  }

  @Override
  public Timer timer(String name) {
    return new ReplayAwareTimer(scope.timer(name), context);
  }

  @Override
  public Histogram histogram(String name, Buckets buckets) {
    return new ReplayAwareHistogram(scope.histogram(name, buckets), context);
  }

  @Override
  public Scope tagged(Map<String, String> tags) {
    return new ReplayAwareScope(scope.tagged(tags), context);
  }

  @Override
  public Scope subScope(String name) {
    return new ReplayAwareScope(scope.subScope(name), context);
  }

  @Override
  public Capabilities capabilities() {
    return scope.capabilities();
  }

  @Override
  public void close() throws ScopeCloseException {
    scope.close();
  }
}
