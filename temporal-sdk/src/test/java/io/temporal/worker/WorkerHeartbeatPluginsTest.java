package io.temporal.worker;

import static org.junit.Assert.assertEquals;

import io.temporal.api.worker.v1.PluginInfo;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.SimplePlugin;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote("This test directly creates and controls a local test service.")
@Category(RequiresLocalServer.class)
public class WorkerHeartbeatPluginsTest {

  @Test
  public void pluginRegisteredOnClientAndFactoryIsReportedOnce() {
    SimplePlugin sharedPlugin = SimplePlugin.newBuilder("shared-plugin").build();
    SimplePlugin factoryPlugin = SimplePlugin.newBuilder("factory-plugin").build();
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setPlugins(sharedPlugin).build())
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder().setPlugins(sharedPlugin, factoryPlugin).build())
            .build();

    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(options)) {
      Worker worker = env.newWorker("test-queue");
      env.start();

      List<String> reportedPlugins =
          worker.buildHeartbeatCallback("test-worker-group", null).get().getPluginsList().stream()
              .map(PluginInfo::getName)
              .collect(Collectors.toList());
      assertEquals(Arrays.asList("shared-plugin", "factory-plugin"), reportedPlugins);
    }
  }
}
