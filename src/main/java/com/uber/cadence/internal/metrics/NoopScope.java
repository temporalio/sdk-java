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
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import com.uber.m3.util.Duration;
import java.util.Map;

public final class NoopScope {
  private static class NoopReporter implements StatsReporter {

    @Override
    public void reportCounter(String name, Map<String, String> tags, long value) {}

    @Override
    public void reportGauge(String name, Map<String, String> tags, double value) {}

    @Override
    public void reportTimer(String name, Map<String, String> tags, Duration interval) {}

    @Override
    public void reportHistogramValueSamples(
        String name,
        Map<String, String> tags,
        Buckets buckets,
        double bucketLowerBound,
        double bucketUpperBound,
        long samples) {}

    @Override
    public void reportHistogramDurationSamples(
        String name,
        Map<String, String> tags,
        Buckets buckets,
        Duration bucketLowerBound,
        Duration bucketUpperBound,
        long samples) {}

    @Override
    public Capabilities capabilities() {
      return CapableOf.REPORTING_TAGGING;
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  private NoopScope() {}

  private static Scope instance;

  public static synchronized Scope getInstance() {
    if (instance == null) {
      instance =
          new RootScopeBuilder().reporter(new NoopReporter()).reportEvery(Duration.ofMinutes(1));
    }
    return instance;
  }
}
