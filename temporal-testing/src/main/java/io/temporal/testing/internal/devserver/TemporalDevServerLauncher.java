package io.temporal.testing.internal.devserver;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.testing.TemporalDevServerOptions;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Internal ProcessBuilder-based Temporal dev-server launcher. */
public final class TemporalDevServerLauncher {
  private static final int LOG_TAIL_LINES = 200;
  private static final long GRACEFUL_SHUTDOWN_SECONDS = 10;

  private TemporalDevServerLauncher() {}

  public static RunningServer start(String namespace, TemporalDevServerOptions options) {
    Path executable = TemporalDevServerDownloader.prepare(options);
    int port = options.getPort() == null ? reservePort(options.getIp()) : options.getPort();
    String target = targetHost(options.getIp()) + ":" + port;
    List<String> command = buildCommand(executable, namespace, options, port);

    Process process = null;
    File logFile = options.getLogFile() == null ? null : new File(options.getLogFile());
    try {
      ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
      if (options.getWorkingDirectory() != null) {
        File workingDirectory = new File(options.getWorkingDirectory());
        if (!workingDirectory.isDirectory() && !workingDirectory.mkdirs()) {
          throw new IOException("Unable to create working directory " + workingDirectory);
        }
        processBuilder.directory(workingDirectory);
      }
      configureOutput(processBuilder, logFile);
      process = processBuilder.start();
      waitUntilReady(process, target, namespace, options, command, logFile);
      return new RunningServer(target, process);
    } catch (Throwable failure) {
      stopProcess(process);
      if (failure instanceof Error) {
        throw (Error) failure;
      }
      if (failure instanceof IllegalStateException) {
        throw (IllegalStateException) failure;
      }
      throw startupFailure("Unable to start Temporal dev server", command, logFile, failure);
    }
  }

  private static void configureOutput(ProcessBuilder processBuilder, File logFile)
      throws IOException {
    if (logFile == null) {
      processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
      return;
    }
    File parent = logFile.getAbsoluteFile().getParentFile();
    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
      throw new IOException("Unable to create log directory " + parent);
    }
    processBuilder.redirectOutput(ProcessBuilder.Redirect.to(logFile));
  }

  static List<String> buildCommand(
      Path executable, String namespace, TemporalDevServerOptions options, int port) {
    List<String> command = new ArrayList<>();
    command.add(executable.toAbsolutePath().toString());
    command.add("server");
    command.add("start-dev");
    command.add("--port");
    command.add(Integer.toString(port));
    command.add("--namespace");
    command.add(namespace);
    command.add("--ip");
    command.add(options.getIp());
    command.add("--log-format");
    command.add(options.getLogFormat());
    command.add("--log-level");
    command.add(options.getLogLevel());
    // Keep these defaults in sync with sdk-core's TemporalDevServerConfig.
    command.add("--dynamic-config-value");
    command.add("frontend.enableServerVersionCheck=false");
    command.add("--dynamic-config-value");
    command.add("frontend.enableUpdateWorkflowExecution=true");
    command.add("--dynamic-config-value");
    command.add("frontend.enableUpdateWorkflowExecutionAsyncAccepted=true");
    if (options.getDatabaseFilename() != null) {
      command.add("--db-filename");
      command.add(options.getDatabaseFilename());
    }
    if (options.getUiPort() != null) {
      command.add("--ui-port");
      command.add(Integer.toString(options.getUiPort()));
    } else if (options.isUiEnabled()) {
      command.add("--ui-port");
      command.add(Integer.toString(Math.min(65535, port + 1000)));
    } else {
      command.add("--headless");
    }
    command.addAll(options.getExtraArgs());
    return command;
  }

  private static void waitUntilReady(
      Process process,
      String target,
      String namespace,
      TemporalDevServerOptions options,
      List<String> command,
      File logFile) {
    long timeoutNanos = options.getStartupTimeout().toNanos();
    long startNanos = System.nanoTime();
    long deadlineNanos =
        Long.MAX_VALUE - startNanos < timeoutNanos ? Long.MAX_VALUE : startNanos + timeoutNanos;
    ManagedChannel channel =
        ManagedChannelBuilder.forTarget(target).usePlaintext().directExecutor().build();
    Throwable lastFailure = null;
    try {
      while (System.nanoTime() < deadlineNanos) {
        if (!process.isAlive()) {
          throw startupFailure(
              "Temporal dev server exited prematurely with code " + process.exitValue(),
              command,
              logFile,
              lastFailure);
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        long rpcNanos = Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(1), remainingNanos));
        try {
          HealthCheckResponse health =
              HealthGrpc.newBlockingStub(channel)
                  .withDeadlineAfter(rpcNanos, TimeUnit.NANOSECONDS)
                  .check(
                      HealthCheckRequest.newBuilder()
                          .setService(WorkflowServiceGrpc.SERVICE_NAME)
                          .build());
          if (health.getStatus() != HealthCheckResponse.ServingStatus.SERVING) {
            throw new IllegalStateException("gRPC health service is " + health.getStatus());
          }
          WorkflowServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(rpcNanos, TimeUnit.NANOSECONDS)
              .describeNamespace(
                  DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build());
          return;
        } catch (StatusRuntimeException | IllegalStateException e) {
          lastFailure = e;
        }
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw startupFailure(
              "Interrupted while waiting for Temporal dev server", command, logFile, e);
        }
      }
      throw startupFailure(
          "Temporal dev server did not become ready within " + options.getStartupTimeout(),
          command,
          logFile,
          lastFailure);
    } finally {
      channel.shutdownNow();
      try {
        channel.awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static IllegalStateException startupFailure(
      String message, List<String> command, File logFile, Throwable cause) {
    return new IllegalStateException(
        message
            + "\nCommand: "
            + renderCommand(command)
            + "\nTemporal dev-server output tail:\n"
            + readOutputTail(logFile),
        cause);
  }

  private static String readOutputTail(File logFile) {
    if (logFile == null) {
      return "<output inherited by parent process>";
    }
    if (!logFile.isFile()) {
      return "<no server output>";
    }
    Deque<String> tail = new ArrayDeque<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new FileInputStream(logFile), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        tail.addLast(line);
        while (tail.size() > LOG_TAIL_LINES) {
          tail.removeFirst();
        }
      }
    } catch (IOException e) {
      return "<failed reading server output: " + e + ">";
    }
    if (tail.isEmpty()) {
      return "<no server output>";
    }
    return String.join(System.lineSeparator(), tail);
  }

  private static String renderCommand(List<String> command) {
    StringBuilder rendered = new StringBuilder();
    for (String part : command) {
      if (rendered.length() > 0) {
        rendered.append(' ');
      }
      if (part.indexOf(' ') >= 0) {
        rendered.append('"').append(part.replace("\"", "\\\"")).append('"');
      } else {
        rendered.append(part);
      }
    }
    return rendered.toString();
  }

  private static int reservePort(String ip) {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName(ip))) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException("Unable to reserve a port on " + ip, e);
    }
  }

  private static String targetHost(String ip) {
    if ("0.0.0.0".equals(ip) || "::".equals(ip) || "0:0:0:0:0:0:0:0".equals(ip)) {
      return "127.0.0.1";
    }
    return ip.indexOf(':') >= 0 ? "[" + ip + "]" : ip;
  }

  private static void stopProcess(Process process) {
    if (process == null || !process.isAlive()) {
      return;
    }
    process.destroy();
    try {
      if (!process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        process.waitFor(GRACEFUL_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  public static final class RunningServer implements AutoCloseable {
    private final String target;
    private final Process process;
    private final AtomicBoolean closed = new AtomicBoolean();

    private RunningServer(String target, Process process) {
      this.target = target;
      this.process = process;
    }

    public String getTarget() {
      return target;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      stopProcess(process);
    }
  }
}
