package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.testing.internal.devserver.SdkJavaTestServerProfile;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/** Integration coverage for dev-server ownership using sdk-java's pinned real Temporal CLI. */
@EnabledIfSystemProperty(named = SdkJavaTestServerProfile.ACTIVE_PROPERTY, matches = "true")
class TemporalDevServerIntegrationTest {
  private static Path temporalCli;

  @TempDir Path tempDirectory;

  @BeforeAll
  static void prepareTemporalCli() {
    temporalCli = SdkJavaTestServerProfile.prepare();
  }

  @Test
  void standaloneServerBecomesReadyAndCloseIsIdempotent() throws Exception {
    TemporalDevServer server = TemporalDevServer.start("MyNamespace", realServerOptions().build());
    String target = server.getTarget();

    assertEquals("MyNamespace", server.getNamespace());
    assertTrue(canConnect(target));

    server.close();
    server.close();

    assertTrue(awaitClosed(target));
  }

  @Test
  void prematureExitIncludesCommandAndCliOutput() {
    TemporalDevServerOptions options =
        realServerOptions().setExtraArgs("--definitely-not-a-real-temporal-cli-argument").build();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> TemporalDevServer.start("default", options));

    assertTrue(failure.getMessage().contains("exited prematurely"));
    assertTrue(failure.getMessage().contains("--definitely-not-a-real-temporal-cli-argument"));
    assertTrue(failure.getMessage().contains("Temporal dev-server output tail"));
  }

  @Test
  void startupTimeoutStopsCliProcess() throws Exception {
    int port = availablePort();
    TemporalDevServerOptions options =
        realServerOptions().setPort(port).setStartupTimeout(Duration.ofNanos(1)).build();

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class, () -> TemporalDevServer.start("default", options));

    assertTrue(failure.getMessage().contains("did not become ready"));
    assertTrue(awaitClosed("127.0.0.1:" + port));
  }

  @Test
  void environmentOwnsServerAndUsesEnvironmentNamespace() throws Exception {
    String firstTarget;
    try (TestWorkflowEnvironment environment =
        TestWorkflowEnvironment.startLocal(realServerOptions().build())) {
      assertEquals("default", environment.getNamespace());
      firstTarget = environment.getWorkflowServiceStubs().getOptions().getTarget();
      assertTrue(canConnect(firstTarget));
    }
    assertTrue(awaitClosed(firstTarget));

    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setNamespace("Authoritative").build())
            .build();
    String combinedTarget;
    try (TestWorkflowEnvironment environment =
        TestWorkflowEnvironment.startLocal(testOptions, realServerOptions().build())) {
      assertEquals("Authoritative", environment.getNamespace());
      combinedTarget = environment.getWorkflowServiceStubs().getOptions().getTarget();
      assertTrue(canConnect(combinedTarget));
    }
    assertTrue(awaitClosed(combinedTarget));
  }

  @Test
  void constructionFailureStopsPartiallyStartedServer() throws Exception {
    int port = availablePort();
    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder()
                    .setInterceptors(
                        new WorkflowClientInterceptorBase() {
                          @Override
                          public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
                              WorkflowClientCallsInterceptor next) {
                            throw new DeliberateTestFailure();
                          }
                        })
                    .build())
            .build();

    assertThrows(
        DeliberateTestFailure.class,
        () ->
            TestWorkflowEnvironment.startLocal(
                testOptions, realServerOptions().setPort(port).build()));

    assertTrue(awaitClosed("127.0.0.1:" + port));
  }

  @Test
  void junit4RuleClosesServerAfterSuccessAndFailure() throws Throwable {
    TestWorkflowRule successfulRule =
        TestWorkflowRule.newBuilder()
            .useDevServer(realServerOptions().build())
            .setWorkflowTypes()
            .build();
    String successTarget = successfulRule.getWorkflowServiceStubs().getOptions().getTarget();
    successfulRule
        .apply(
            new Statement() {
              @Override
              public void evaluate() {}
            },
            Description.createTestDescription(getClass(), "successfulRule"))
        .evaluate();
    assertTrue(awaitClosed(successTarget));

    TestWorkflowRule failingRule =
        TestWorkflowRule.newBuilder()
            .useDevServer(realServerOptions().build())
            .setWorkflowTypes()
            .build();
    String failureTarget = failingRule.getWorkflowServiceStubs().getOptions().getTarget();
    assertThrows(
        DeliberateTestFailure.class,
        () ->
            failingRule
                .apply(
                    new Statement() {
                      @Override
                      public void evaluate() {
                        throw new DeliberateTestFailure();
                      }
                    },
                    Description.createTestDescription(getClass(), "failingRule"))
                .evaluate());
    assertTrue(awaitClosed(failureTarget));
  }

  private TemporalDevServerOptions.Builder realServerOptions() {
    return TemporalDevServerOptions.newBuilder()
        .setExistingPath(temporalCli.toString())
        .setStartupTimeout(Duration.ofSeconds(60))
        .setLogFile(tempDirectory.resolve("server-" + System.nanoTime() + ".log").toString());
  }

  private static int availablePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static boolean awaitClosed(String target) throws InterruptedException {
    for (int i = 0; i < 50; i++) {
      if (!canConnect(target)) {
        return true;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return false;
  }

  private static boolean canConnect(String target) {
    int separator = target.lastIndexOf(':');
    String host = target.substring(0, separator);
    int port = Integer.parseInt(target.substring(separator + 1));
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(host, port), 250);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static final class DeliberateTestFailure extends RuntimeException {}
}
