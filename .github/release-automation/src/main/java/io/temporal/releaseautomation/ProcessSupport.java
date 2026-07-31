package io.temporal.releaseautomation;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class ProcessSupport {
  private ProcessSupport() {}

  static List<String> run(Path workingDirectory, List<String> command, Map<String, String> env) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workingDirectory.toFile());
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    builder.environment().putAll(env);
    ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    try {
      ActivityExecutionContext activityContext = Activity.getExecutionContext();
      Process process = builder.start();
      heartbeat.scheduleAtFixedRate(
          () -> activityContext.heartbeat("External release command is running."),
          15,
          15,
          TimeUnit.SECONDS);
      List<String> output = new ArrayList<>();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.add(line);
        }
      }
      int status = process.waitFor();
      if (status != 0) {
        throw new CommandFailedException(status, command.get(0));
      }
      return output;
    } catch (IOException e) {
      throw new IllegalStateException("Unable to start release command.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Release command was interrupted.", e);
    } finally {
      heartbeat.shutdownNow();
      try {
        heartbeat.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  static final class CommandFailedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    CommandFailedException(int status, String command) {
      super(command + " exited with status " + status + ".");
      this.status = status;
    }

    int getStatus() {
      return status;
    }
  }
}
