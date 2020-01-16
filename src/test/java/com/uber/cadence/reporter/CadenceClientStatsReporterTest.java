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

package com.uber.cadence.reporter;

import static org.junit.Assert.assertEquals;

import com.uber.m3.tally.CapableOf;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CadenceClientStatsReporterTest {

  private static final String DEFAULT_REPORT_NAME = "cadence_workflow_start";
  private static final Map<String, String> DEFAULT_REPORT_TAGS =
      ImmutableMap.of("Domain", "domain_name", "TaskList", "task_list");
  private static final long DEFAULT_COUNT = 10;
  private static final Duration DEFAULT_DURATION = Duration.ofSeconds(10);

  private CadenceClientStatsReporter cadenceClientStatsReporter = new CadenceClientStatsReporter();

  @Before
  public void init() {
    Metrics.addRegistry(new SimpleMeterRegistry());
  }

  @After
  public void cleanup() {
    Metrics.globalRegistry.getMeters().forEach(Metrics.globalRegistry::remove);
  }

  @Test
  public void testReporterCapabilitiesShouldReturnReporting() {
    assertEquals(CapableOf.REPORTING, cadenceClientStatsReporter.capabilities());
  }

  @Test
  public void testCounterShouldCallMetricRegistryForMonitoredCounterCadenceAction() {
    callDefaultCounter();

    assertEquals(
        Arrays.asList(Tag.of("Domain", "domain_name"), Tag.of("TaskList", "task_list")),
        Metrics.globalRegistry.get(DEFAULT_REPORT_NAME).counter().getId().getTags());
    assertEquals(10, Metrics.globalRegistry.get(DEFAULT_REPORT_NAME).counter().count(), 0);
  }

  @Test
  public void testTimerShouldCallMetricRegistryForMonitoredCounterCadenceAction() {
    callDefaultTimer();

    assertEquals(
        Arrays.asList(Tag.of("Domain", "domain_name"), Tag.of("TaskList", "task_list")),
        Metrics.globalRegistry.get(DEFAULT_REPORT_NAME).timer().getId().getTags());
    assertEquals(
        10, Metrics.globalRegistry.get(DEFAULT_REPORT_NAME).timer().totalTime(TimeUnit.SECONDS), 0);
  }

  private void callDefaultCounter() {
    cadenceClientStatsReporter.reportCounter(
        DEFAULT_REPORT_NAME, DEFAULT_REPORT_TAGS, DEFAULT_COUNT);
  }

  private void callDefaultTimer() {
    cadenceClientStatsReporter.reportTimer(
        DEFAULT_REPORT_NAME, DEFAULT_REPORT_TAGS, DEFAULT_DURATION);
  }
}
