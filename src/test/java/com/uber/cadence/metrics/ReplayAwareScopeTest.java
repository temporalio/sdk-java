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

package com.uber.cadence.metrics;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.cadence.internal.metrics.ReplayAwareScope;
import com.uber.cadence.internal.replay.ReplayAware;
import com.uber.m3.tally.Buckets;
import com.uber.m3.tally.Counter;
import com.uber.m3.tally.Gauge;
import com.uber.m3.tally.Histogram;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.Stopwatch;
import com.uber.m3.tally.Timer;
import com.uber.m3.tally.ValueBuckets;
import com.uber.m3.util.Duration;
import java.util.function.Supplier;
import org.junit.Test;

public class ReplayAwareScopeTest {

  private static class TestContext implements ReplayAware {
    boolean isReplaying;

    public TestContext(boolean isReplaying) {
      this.isReplaying = isReplaying;
    }

    @Override
    public boolean isReplaying() {
      return isReplaying;
    }
  }

  @Test
  public void testReplayAwareScopeReplaying() {
    Scope scope = mock(Scope.class);
    Counter counter = mock(Counter.class);
    Gauge gauge = mock(Gauge.class);
    Timer timer = mock(Timer.class);
    Histogram histogram = mock(Histogram.class);

    Buckets buckets = ValueBuckets.linear(0, 10, 10);
    when(scope.counter("test-counter")).thenReturn(counter);
    when(scope.gauge("test-gauge")).thenReturn(gauge);
    when(scope.timer("test-timer")).thenReturn(timer);
    when(scope.histogram("test-histogram", buckets)).thenReturn(histogram);

    TestContext context = new TestContext(true);
    Scope replayAwareScope = new ReplayAwareScope(scope, context, System::currentTimeMillis);

    replayAwareScope.counter("test-counter").inc(1);
    replayAwareScope.gauge("test-gauge").update(100.0);
    replayAwareScope.timer("test-timer").record(Duration.ofMillis(100));
    replayAwareScope.histogram("test-histogram", buckets).recordValue(10);
    replayAwareScope.histogram("test-histogram", buckets).recordDuration(Duration.ofHours(1));

    verify(counter, never()).inc(1);
    verify(gauge, never()).update(100.0);
    verify(timer, never()).record(Duration.ofMillis(100));
    verify(histogram, never()).recordValue(10);
    verify(histogram, never()).recordDuration(Duration.ofHours(1));
  }

  @Test
  public void testReplayAwareScopeNotReplaying() {
    Scope scope = mock(Scope.class);
    Counter counter = mock(Counter.class);
    Gauge gauge = mock(Gauge.class);
    Timer timer = mock(Timer.class);
    Histogram histogram = mock(Histogram.class);

    Buckets buckets = ValueBuckets.linear(0, 10, 10);
    when(scope.counter("test-counter")).thenReturn(counter);
    when(scope.gauge("test-gauge")).thenReturn(gauge);
    when(scope.timer("test-timer")).thenReturn(timer);
    when(scope.histogram("test-histogram", buckets)).thenReturn(histogram);

    TestContext context = new TestContext(false);
    Scope replayAwareScope = new ReplayAwareScope(scope, context, System::currentTimeMillis);

    replayAwareScope.counter("test-counter").inc(1);
    replayAwareScope.gauge("test-gauge").update(100.0);
    replayAwareScope.timer("test-timer").record(Duration.ofMillis(100));
    replayAwareScope.histogram("test-histogram", buckets).recordValue(10);
    replayAwareScope.histogram("test-histogram", buckets).recordDuration(Duration.ofHours(1));

    verify(counter, times(1)).inc(1);
    verify(gauge, times(1)).update(100.0);
    verify(timer, times(1)).record(Duration.ofMillis(100));
    verify(histogram, times(1)).recordValue(10);
    verify(histogram, times(1)).recordDuration(Duration.ofHours(1));
  }

  static class TestClock implements Supplier<Long> {
    private long currTime;

    @Override
    public Long get() {
      return currTime;
    }

    void setTime(long currTime) {
      this.currTime = currTime;
    }
  }

  @Test
  public void testCustomClockForTimer() {
    Scope scope = mock(Scope.class);
    Timer timer = mock(Timer.class);
    Histogram histogram = mock(Histogram.class);

    Buckets buckets = ValueBuckets.linear(0, 10, 10);
    when(scope.timer("test-timer")).thenReturn(timer);
    when(scope.histogram("test-histogram", buckets)).thenReturn(histogram);

    TestContext context = new TestContext(false);
    TestClock clock = new TestClock();
    clock.setTime(0);
    Scope replayAwareScope = new ReplayAwareScope(scope, context, clock);
    Stopwatch sw = replayAwareScope.timer("test-timer").start();
    clock.setTime(100);
    sw.stop();

    sw = replayAwareScope.histogram("test-histogram", buckets).start();
    clock.setTime(150);
    sw.stop();

    verify(timer, times(1)).record(Duration.ofMillis(100));
    verify(histogram, times(1)).recordDuration(Duration.ofMillis(50));
  }
}
