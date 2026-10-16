package io.temporal.worker;

import static org.junit.Assert.assertEquals;

import io.temporal.api.worker.v1.PluginInfo;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.SimplePlugin;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@CloudTestExclusionNote("This test directly creates and controls a local test service.")
@Category(RequiresLocalServer.class)
public class WorkerHeartbeatPluginsTest {

  @Test
  public void pluginRegisteredOnClientAndFactoryIsReportedOnce() {
    SimplePlugin plugin = SimplePlugin.newBuilder("test-plugin").build();

    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
      WorkflowClient client =
          WorkflowClient.newInstance(
              env.getWorkflowServiceStubs(),
              WorkflowClientOptions.newBuilder()
                  .setNamespace(env.getNamespace())
                  .setPlugins(plugin)
                  .build());
      WorkerFactory factory =
          WorkerFactory.newInstance(
              client, WorkerFactoryOptions.newBuilder().setPlugins(plugin).build());
      Worker worker = factory.newWorker("test-queue");
      factory.start();

      List<String> reportedPlugins =
          worker.buildHeartbeatCallback("test-worker-group", null).get().getPluginsList().stream()
              .map(PluginInfo::getName)
              .collect(Collectors.toList());
      assertEquals(Collections.singletonList("test-plugin"), reportedPlugins);

      factory.shutdownNow();
    }
  }
}
