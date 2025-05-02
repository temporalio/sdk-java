package io.temporal.common.reporter;

import static org.junit.Assert.assertEquals;

import com.uber.m3.tally.CapableOf;
import com.uber.m3.util.Duration;
import com.uber.m3.util.ImmutableMap;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class MicrometerClientStatsReporterTest {

  private static final String DEFAULT_REPORT_NAME = "temporal_workflow_start";
  private static final Map<String, String> DEFAULT_REPORT_TAGS =
      ImmutableMap.of("Namespace", "namespace_name", "TaskQueue", "task_queue");
  private static final long DEFAULT_COUNT = 10;
  private static final Duration DEFAULT_DURATION = Duration.ofSeconds(10);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  private final MicrometerClientStatsReporter micrometerClientStatsReporter =
      new MicrometerClientStatsReporter(registry);

  @Test
  public void testReporterCapabilitiesShouldReturnReporting() {
    assertEquals(CapableOf.REPORTING, micrometerClientStatsReporter.capabilities());
  }

  @Test
  public void testCounterShouldCallMetricRegistryForMonitoredCounterTemporalAction() {
    callDefaultCounter();

    assertEquals(
        Arrays.asList(Tag.of("Namespace", "namespace_name"), Tag.of("TaskQueue", "task_queue")),
        registry.get(DEFAULT_REPORT_NAME).counter().getId().getTags());
    assertEquals(10, registry.get(DEFAULT_REPORT_NAME).counter().count(), 0);
  }

  @Test
  public void testTimerShouldCallMetricRegistryForMonitoredCounterTemporalAction() {
    callDefaultTimer();

    assertEquals(
        Arrays.asList(Tag.of("Namespace", "namespace_name"), Tag.of("TaskQueue", "task_queue")),
        registry.get(DEFAULT_REPORT_NAME).timer().getId().getTags());
    assertEquals(10, registry.get(DEFAULT_REPORT_NAME).timer().totalTime(TimeUnit.SECONDS), 0);
  }

  private void callDefaultCounter() {
    micrometerClientStatsReporter.reportCounter(
        DEFAULT_REPORT_NAME, DEFAULT_REPORT_TAGS, DEFAULT_COUNT);
  }

  private void callDefaultTimer() {
    micrometerClientStatsReporter.reportTimer(
        DEFAULT_REPORT_NAME, DEFAULT_REPORT_TAGS, DEFAULT_DURATION);
  }
}
