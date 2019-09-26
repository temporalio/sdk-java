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
import com.uber.m3.tally.StopwatchRecorder;
import com.uber.m3.tally.Timer;
import com.uber.m3.util.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ReplayAwareScope implements Scope {
  private final Scope scope;
  private final ReplayAware context;
  private final Supplier<Long> clock;

  public ReplayAwareScope(Scope scope, ReplayAware context, Supplier<Long> clock) {
    this.scope = Objects.requireNonNull(scope);
    this.context = Objects.requireNonNull(context);
    this.clock = Objects.requireNonNull(clock);
  }

  private class ReplayAwareCounter implements Counter {
    Counter counter;

    ReplayAwareCounter(Counter counter) {
      this.counter = Objects.requireNonNull(counter);
    }

    @Override
    public void inc(long delta) {
      if (context.isReplaying()) {
        return;
      }

      counter.inc(delta);
    }
  }

  private class ReplayAwareGauge implements Gauge {
    Gauge gauge;

    ReplayAwareGauge(Gauge gauge) {
      this.gauge = Objects.requireNonNull(gauge);
    }

    @Override
    public void update(double value) {
      if (context.isReplaying()) {
        return;
      }

      gauge.update(value);
    }
  }

  private class ReplayAwareTimer implements Timer, DurationRecorder {
    Timer timer;

    ReplayAwareTimer(Timer timer) {
      this.timer = Objects.requireNonNull(timer);
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
      long startNanos = TimeUnit.MILLISECONDS.toNanos(clock.get());
      return new Stopwatch(startNanos, new ReplayAwareStopwatchRecorder(this));
    }

    @Override
    public void recordDuration(Duration interval) {
      record(interval);
    }
  }

  interface DurationRecorder {
    void recordDuration(Duration interval);
  }

  private class ReplayAwareStopwatchRecorder implements StopwatchRecorder {
    DurationRecorder recorder;

    ReplayAwareStopwatchRecorder(DurationRecorder recorder) {
      this.recorder = recorder;
    }

    @Override
    public void recordStopwatch(long startNanos) {
      long endNanos = TimeUnit.MILLISECONDS.toNanos(clock.get());
      recorder.recordDuration(Duration.between(startNanos, endNanos));
    }
  }

  private class ReplayAwareHistogram implements Histogram, DurationRecorder {
    Histogram histogram;

    ReplayAwareHistogram(Histogram histogram) {
      this.histogram = Objects.requireNonNull(histogram);
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
      long startNanos = TimeUnit.MILLISECONDS.toNanos(clock.get());
      return new Stopwatch(startNanos, new ReplayAwareStopwatchRecorder(this));
    }
  }

  @Override
  public Counter counter(String name) {
    return new ReplayAwareCounter(scope.counter(name));
  }

  @Override
  public Gauge gauge(String name) {
    return new ReplayAwareGauge(scope.gauge(name));
  }

  @Override
  public Timer timer(String name) {
    return new ReplayAwareTimer(scope.timer(name));
  }

  @Override
  public Histogram histogram(String name, Buckets buckets) {
    return new ReplayAwareHistogram(scope.histogram(name, buckets));
  }

  @Override
  public Scope tagged(Map<String, String> tags) {
    return new ReplayAwareScope(scope.tagged(tags), context, clock);
  }

  @Override
  public Scope subScope(String name) {
    return new ReplayAwareScope(scope.subScope(name), context, clock);
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
