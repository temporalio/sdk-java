/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.common;

import static org.junit.Assert.*;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PluginTest {

  @Test
  public void testPluginBaseName() {
    PluginBase plugin = new PluginBase("test-plugin") {
          // empty implementation
        };
    assertEquals("test-plugin", plugin.getName());
  }

  @Test
  public void testPluginBaseToString() {
    PluginBase plugin = new PluginBase("my-plugin") {
          // empty implementation
        };
    assertTrue(plugin.toString().contains("my-plugin"));
  }

  @Test(expected = NullPointerException.class)
  public void testPluginBaseNullName() {
    new PluginBase(null) {
      // empty implementation
    };
  }

  @Test
  public void testClientPluginDefaultMethods() throws Exception {
    io.temporal.client.Plugin plugin =
        new io.temporal.client.Plugin() {
          @Override
          public String getName() {
            return "test";
          }
        };

    // Test default configureServiceStubs returns same builder
    WorkflowServiceStubsOptions.Builder stubsBuilder = WorkflowServiceStubsOptions.newBuilder();
    assertSame(stubsBuilder, plugin.configureServiceStubs(stubsBuilder));

    // Test default configureClient returns same builder
    WorkflowClientOptions.Builder clientBuilder = WorkflowClientOptions.newBuilder();
    assertSame(clientBuilder, plugin.configureClient(clientBuilder));
  }

  @Test
  public void testWorkerPluginDefaultMethods() throws Exception {
    io.temporal.worker.Plugin plugin =
        new io.temporal.worker.Plugin() {
          @Override
          public String getName() {
            return "test";
          }
        };

    // Test default configureWorkerFactory returns same builder
    WorkerFactoryOptions.Builder factoryBuilder = WorkerFactoryOptions.newBuilder();
    assertSame(factoryBuilder, plugin.configureWorkerFactory(factoryBuilder));

    // Test default configureWorker returns same builder
    WorkerOptions.Builder workerBuilder = WorkerOptions.newBuilder();
    assertSame(workerBuilder, plugin.configureWorker("test-queue", workerBuilder));

    // Test startWorkerFactory calls next
    final boolean[] called = {false};
    plugin.startWorkerFactory(null, () -> called[0] = true);
    assertTrue("startWorkerFactory should call next", called[0]);

    // Test default initializeWorker is a no-op (doesn't throw)
    plugin.initializeWorker("test-queue", null);
  }

  @Test
  public void testConfigurationPhaseOrder() {
    List<String> order = new ArrayList<>();

    PluginBase pluginA = createTrackingPlugin("A", order);
    PluginBase pluginB = createTrackingPlugin("B", order);
    PluginBase pluginC = createTrackingPlugin("C", order);

    List<Object> plugins = Arrays.asList(pluginA, pluginB, pluginC);

    // Simulate configuration phase (forward order)
    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    for (Object plugin : plugins) {
      if (plugin instanceof io.temporal.client.Plugin) {
        builder = ((io.temporal.client.Plugin) plugin).configureClient(builder);
      }
    }

    // Configuration should be in forward order
    assertEquals(Arrays.asList("A-config", "B-config", "C-config"), order);
  }

  @Test
  public void testExecutionPhaseReverseOrder() throws Exception {
    List<String> order = new ArrayList<>();

    PluginBase pluginA = createExecutionTrackingPlugin("A", order);
    PluginBase pluginB = createExecutionTrackingPlugin("B", order);
    PluginBase pluginC = createExecutionTrackingPlugin("C", order);

    List<Object> plugins = Arrays.asList(pluginA, pluginB, pluginC);

    // Build chain in reverse (like WorkerFactory does)
    Runnable chain =
        () -> {
          order.add("terminal");
        };

    List<Object> reversed = new ArrayList<>(plugins);
    java.util.Collections.reverse(reversed);
    for (Object plugin : reversed) {
      if (plugin instanceof io.temporal.worker.Plugin) {
        final Runnable next = chain;
        final io.temporal.worker.Plugin workerPlugin = (io.temporal.worker.Plugin) plugin;
        chain =
            () -> {
              order.add(workerPlugin.getName() + "-before");
              try {
                workerPlugin.startWorkerFactory(null, next);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              order.add(workerPlugin.getName() + "-after");
            };
      }
    }

    // Execute the chain
    chain.run();

    // First plugin should wrap all others
    assertEquals(
        Arrays.asList(
            "A-before", "B-before", "C-before", "terminal", "C-after", "B-after", "A-after"),
        order);
  }

  @Test
  public void testPluginBaseImplementsBothInterfaces() {
    PluginBase plugin = new PluginBase("dual-plugin") {
          // empty implementation
        };

    assertTrue(
        "PluginBase should implement client Plugin", plugin instanceof io.temporal.client.Plugin);
    assertTrue(
        "PluginBase should implement worker Plugin", plugin instanceof io.temporal.worker.Plugin);
  }

  private PluginBase createTrackingPlugin(String name, List<String> order) {
    return new PluginBase(name) {
      @Override
      public WorkflowClientOptions.Builder configureClient(WorkflowClientOptions.Builder builder) {
        order.add(name + "-config");
        return builder;
      }
    };
  }

  private PluginBase createExecutionTrackingPlugin(String name, List<String> order) {
    return new PluginBase(name) {
      @Override
      public void startWorkerFactory(io.temporal.worker.WorkerFactory factory, Runnable next) {
        next.run();
      }
    };
  }
}
