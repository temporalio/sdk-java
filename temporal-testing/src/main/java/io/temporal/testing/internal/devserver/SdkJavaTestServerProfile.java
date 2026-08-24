package io.temporal.testing.internal.devserver;

import io.temporal.testing.TemporalDevServer;
import io.temporal.testing.TemporalDevServerOptions;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;

/** Configuration and lifecycle used only by sdk-java's Gradle test profile. */
public final class SdkJavaTestServerProfile {
  public static final String ACTIVE_PROPERTY = "io.temporal.testing.internal.devServerProfile";

  // This is intentionally the sole Temporal CLI version used by sdk-java repository tests.
  private static final String TEST_CLI_VERSION = "1.7.4-standalone-nexus-operations";
  private static final String TEST_NAMESPACE = "UnitTest";
  private static final String DATABASE_FILENAME = "temporal.sqlite";

  private static TemporalDevServer server;
  private static boolean shutdownHookRegistered;

  private SdkJavaTestServerProfile() {}

  public static boolean isActive() {
    return Boolean.parseBoolean(System.getProperty(ACTIVE_PROPERTY, "false"));
  }

  public static String getTarget() {
    if (!isActive()) {
      return null;
    }
    return "localhost:7233";
  }

  public static synchronized String start() {
    if (server == null) {
      File workingDirectory = workingDirectory();
      cleanDatabase(workingDirectory);
      try {
        server = TemporalDevServer.start(TEST_NAMESPACE, serverOptions(workingDirectory));
      } catch (RuntimeException | Error failure) {
        cleanDatabase(workingDirectory);
        throw failure;
      }
      if (!shutdownHookRegistered) {
        Runtime.getRuntime()
            .addShutdownHook(
                new Thread(SdkJavaTestServerProfile::shutdown, "sdk-java-dev-server-shutdown"));
        shutdownHookRegistered = true;
      }
    }
    return server.getTarget();
  }

  public static Path prepare() {
    return TemporalDevServerDownloader.prepare(downloadOptions());
  }

  public static synchronized void shutdown() {
    if (server != null) {
      server.close();
      server = null;
    }
    cleanDatabase(workingDirectory());
  }

  private static TemporalDevServerOptions serverOptions(@Nonnull File workingDirectory) {
    return TemporalDevServerOptions.newBuilder(downloadOptions())
        .setIp("127.0.0.1")
        .setPort(7233)
        .setUiEnabled(false)
        .setDatabaseFilename(DATABASE_FILENAME)
        .setWorkingDirectory(workingDirectory.getAbsolutePath())
        .setLogFile(new File(workingDirectory, "server.log").getAbsolutePath())
        .setExtraArgs(repositoryServerArguments())
        .build();
  }

  private static TemporalDevServerOptions downloadOptions() {
    return TemporalDevServerOptions.newBuilder().setDownloadVersion("v" + TEST_CLI_VERSION).build();
  }

  private static List<String> repositoryServerArguments() {
    return Arrays.asList(
        "--http-port",
        "7243",
        "--sqlite-pragma",
        "journal_mode=WAL",
        "--sqlite-pragma",
        "synchronous=OFF",
        "--search-attribute",
        "CustomKeywordField=Keyword",
        "--search-attribute",
        "CustomStringField=Text",
        "--search-attribute",
        "CustomTextField=Text",
        "--search-attribute",
        "CustomIntField=Int",
        "--search-attribute",
        "CustomDatetimeField=Datetime",
        "--search-attribute",
        "CustomDoubleField=Double",
        "--search-attribute",
        "CustomBoolField=Bool",
        "--dynamic-config-value",
        "system.enableActivityEagerExecution=true",
        "--dynamic-config-value",
        "history.MaxBufferedQueryCount=10000",
        "--dynamic-config-value",
        "frontend.workerVersioningDataAPIs=true",
        "--dynamic-config-value",
        "history.enableRequestIdRefLinks=true",
        "--dynamic-config-value",
        "frontend.WorkerHeartbeatsEnabled=true",
        "--dynamic-config-value",
        "frontend.ListWorkersEnabled=true",
        "--dynamic-config-value",
        "frontend.enableCancelWorkerPollsOnShutdown=true",
        "--dynamic-config-value",
        "component.callbacks.allowedAddresses=[{\"Pattern\":\"localhost:7243\",\"AllowInsecure\":true}]",
        "--dynamic-config-value",
        "callback.allowedAddresses=[{\"Pattern\":\"localhost:7243\",\"AllowInsecure\":true}]",
        "--dynamic-config-value",
        "frontend.activityAPIsEnabled=true",
        "--dynamic-config-value",
        "activity.enableStandalone=true",
        "--dynamic-config-value",
        "activity.enableCallbacks=true",
        "--dynamic-config-value",
        "activity.startDelayEnabled=true",
        "--dynamic-config-value",
        "nexusoperation.enableStandalone=true",
        "--dynamic-config-value",
        "history.enableChasm=true",
        "--dynamic-config-value",
        "history.enableCHASMSignalBacklinks=true",
        "--dynamic-config-value",
        "history.enableUpdateCallbacks=true",
        "--dynamic-config-value",
        "history.enableCHASMCallbacks=true",
        "--dynamic-config-value",
        "history.enableTransitionHistory=true",
        "--dynamic-config-value",
        "frontend.enableCancelWorkerPollsOnShutdown=true",
        "--dynamic-config-value",
        "frontend.workerCommandsEnabled=true",
        "--dynamic-config-value",
        "system.enableCancelActivityWorkerCommand=true");
  }

  private static File workingDirectory() {
    return new File("build", "temporal-cli/server");
  }

  private static void cleanDatabase(@Nonnull File workingDirectory) {
    for (String name :
        Arrays.asList(DATABASE_FILENAME, DATABASE_FILENAME + "-shm", DATABASE_FILENAME + "-wal")) {
      File file = new File(workingDirectory, name);
      if (file.exists() && !file.delete()) {
        System.err.println("Unable to delete Temporal dev-server database file " + file);
      }
    }
  }
}
