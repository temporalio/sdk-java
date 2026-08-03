package io.temporal.releaseautomation;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCompletionException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

final class ProcessSupport {
  private ProcessSupport() {}

  static List<String> bash(Path script) {
    return Arrays.asList("bash", bashPath(script.toAbsolutePath().toString()));
  }

  static String bashPath(String path) {
    String normalized = path.replace('\\', '/');
    if (normalized.length() >= 3
        && Character.isLetter(normalized.charAt(0))
        && normalized.charAt(1) == ':'
        && normalized.charAt(2) == '/') {
      return "/" + Character.toLowerCase(normalized.charAt(0)) + normalized.substring(2);
    }
    return normalized;
  }

  static List<String> run(Path workingDirectory, List<String> command, Map<String, String> env) {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workingDirectory.toFile());
    builder.redirectError(ProcessBuilder.Redirect.INHERIT);
    Map<String, String> processEnvironment = builder.environment();
    Map<String, String> inheritedEnvironment = new java.util.HashMap<>(processEnvironment);
    processEnvironment.clear();
    for (String name :
        Arrays.asList(
            "PATH",
            "Path",
            "SystemRoot",
            "COMSPEC",
            "PATHEXT",
            "HOME",
            "USERPROFILE",
            "TMPDIR",
            "TMP",
            "TEMP",
            "LANG",
            "LC_ALL",
            "JAVA_HOME",
            "GRAALVM_HOME",
            "CI",
            "SystemDrive")) {
      String value = inheritedEnvironment.get(name);
      if (value != null) {
        processEnvironment.put(name, value);
      }
    }
    processEnvironment.putAll(env);
    ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    try {
      ActivityExecutionContext activityContext = Activity.getExecutionContext();
      Process process = builder.start();
      AtomicReference<ActivityCompletionException> cancellation = new AtomicReference<>();
      heartbeat.scheduleAtFixedRate(
          () -> {
            try {
              activityContext.heartbeat("External release command is running.");
            } catch (ActivityCompletionException e) {
              cancellation.compareAndSet(null, e);
              terminateProcessTree(process);
            }
          },
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
      if (cancellation.get() != null) {
        throw cancellation.get();
      }
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

  static void terminateProcessTree(Process process) {
    List<ProcessHandle> descendants = process.descendants().collect(Collectors.toList());
    for (int index = descendants.size() - 1; index >= 0; index--) {
      descendants.get(index).destroy();
    }
    process.destroy();
    waitForExit(process, descendants, 5);
    for (int index = descendants.size() - 1; index >= 0; index--) {
      ProcessHandle descendant = descendants.get(index);
      if (descendant.isAlive()) {
        descendant.destroyForcibly();
      }
    }
    if (process.isAlive()) {
      process.destroyForcibly();
    }
    waitForExit(process, descendants, 5);
  }

  private static void waitForExit(Process process, List<ProcessHandle> descendants, int seconds) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline
        && (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
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
