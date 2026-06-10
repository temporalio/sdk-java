package io.temporal.worker;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import org.junit.Test;

public class WorkerFactoryOptionsTest {

  @Test
  public void shutdownCheckIntervalDefaultIs250ms() {
    WorkerFactoryOptions options = WorkerFactoryOptions.newBuilder().validateAndBuildWithDefaults();
    assertEquals(Duration.ofMillis(250), options.getShutdownCheckInterval());
  }

  @Test
  public void shutdownCheckIntervalCanBeSet() {
    Duration interval = Duration.ofMillis(5);
    WorkerFactoryOptions options =
        WorkerFactoryOptions.newBuilder().setShutdownCheckInterval(interval).build();
    assertEquals(interval, options.getShutdownCheckInterval());
  }

  @Test
  public void shutdownCheckIntervalSurvivesValidateAndBuild() {
    Duration interval = Duration.ofMillis(10);
    WorkerFactoryOptions options =
        WorkerFactoryOptions.newBuilder()
            .setShutdownCheckInterval(interval)
            .validateAndBuildWithDefaults();
    assertEquals(interval, options.getShutdownCheckInterval());
  }

  @Test
  public void shutdownCheckIntervalSurvivesCopyBuilder() {
    Duration interval = Duration.ofMillis(3);
    WorkerFactoryOptions original =
        WorkerFactoryOptions.newBuilder().setShutdownCheckInterval(interval).build();
    WorkerFactoryOptions copy = WorkerFactoryOptions.newBuilder(original).build();
    assertEquals(interval, copy.getShutdownCheckInterval());
  }

  @Test(expected = IllegalStateException.class)
  public void shutdownCheckIntervalRejectsZero() {
    WorkerFactoryOptions.newBuilder()
        .setShutdownCheckInterval(Duration.ZERO)
        .validateAndBuildWithDefaults();
  }

  @Test(expected = IllegalStateException.class)
  public void shutdownCheckIntervalRejectsNegative() {
    WorkerFactoryOptions.newBuilder()
        .setShutdownCheckInterval(Duration.ofMillis(-1))
        .validateAndBuildWithDefaults();
  }
}
