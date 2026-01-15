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
  public void testSimplePluginName() {
    SimplePlugin plugin = new SimplePlugin("test-plugin") {};
    assertEquals("test-plugin", plugin.getName());
  }

  @Test
  public void testSimplePluginToString() {
    SimplePlugin plugin = new SimplePlugin("my-plugin") {};
    assertTrue(plugin.toString().contains("my-plugin"));
  }

  @Test(expected = NullPointerException.class)
  public void testSimplePluginNullName() {
    new SimplePlugin((String) null) {};
  }

  @Test
  public void testClientPluginDefaultMethods() throws Exception {
    io.temporal.client.ClientPlugin plugin =
        new io.temporal.client.ClientPlugin() {
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
    io.temporal.worker.WorkerPlugin plugin =
        new io.temporal.worker.WorkerPlugin() {
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

    // Test startWorker calls next
    called[0] = false;
    plugin.startWorker("test-queue", null, () -> called[0] = true);
    assertTrue("startWorker should call next", called[0]);

    // Test shutdownWorker calls next
    called[0] = false;
    plugin.shutdownWorker("test-queue", null, () -> called[0] = true);
    assertTrue("shutdownWorker should call next", called[0]);

    // Test default initializeWorker is a no-op (doesn't throw)
    plugin.initializeWorker("test-queue", null);
  }

  @Test
  public void testConfigurationPhaseOrder() {
    List<String> order = new ArrayList<>();

    SimplePlugin pluginA = createTrackingPlugin("A", order);
    SimplePlugin pluginB = createTrackingPlugin("B", order);
    SimplePlugin pluginC = createTrackingPlugin("C", order);

    List<Object> plugins = Arrays.asList(pluginA, pluginB, pluginC);

    // Simulate configuration phase (forward order)
    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    for (Object plugin : plugins) {
      if (plugin instanceof io.temporal.client.ClientPlugin) {
        builder = ((io.temporal.client.ClientPlugin) plugin).configureClient(builder);
      }
    }

    // Configuration should be in forward order
    assertEquals(Arrays.asList("A-config", "B-config", "C-config"), order);
  }

  @Test
  public void testExecutionPhaseReverseOrder() throws Exception {
    List<String> order = new ArrayList<>();

    SimplePlugin pluginA = createExecutionTrackingPlugin("A", order);
    SimplePlugin pluginB = createExecutionTrackingPlugin("B", order);
    SimplePlugin pluginC = createExecutionTrackingPlugin("C", order);

    List<Object> plugins = Arrays.asList(pluginA, pluginB, pluginC);

    // Build chain in reverse (like WorkerFactory does)
    Runnable chain =
        () -> {
          order.add("terminal");
        };

    List<Object> reversed = new ArrayList<>(plugins);
    java.util.Collections.reverse(reversed);
    for (Object plugin : reversed) {
      if (plugin instanceof io.temporal.worker.WorkerPlugin) {
        final Runnable next = chain;
        final io.temporal.worker.WorkerPlugin workerPlugin =
            (io.temporal.worker.WorkerPlugin) plugin;
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
  public void testStartWorkerReverseOrder() throws Exception {
    List<String> order = new ArrayList<>();

    SimplePlugin pluginA = createWorkerLifecycleTrackingPlugin("A", order);
    SimplePlugin pluginB = createWorkerLifecycleTrackingPlugin("B", order);
    SimplePlugin pluginC = createWorkerLifecycleTrackingPlugin("C", order);

    List<Object> plugins = Arrays.asList(pluginA, pluginB, pluginC);

    // Build chain in reverse (like WorkerFactory does)
    Runnable chain = () -> order.add("worker-start");

    List<Object> reversed = new ArrayList<>(plugins);
    java.util.Collections.reverse(reversed);
    for (Object plugin : reversed) {
      if (plugin instanceof io.temporal.worker.WorkerPlugin) {
        final Runnable next = chain;
        final io.temporal.worker.WorkerPlugin workerPlugin =
            (io.temporal.worker.WorkerPlugin) plugin;
        chain =
            () -> {
              order.add(workerPlugin.getName() + "-startWorker-before");
              try {
                workerPlugin.startWorker("test-queue", null, next);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              order.add(workerPlugin.getName() + "-startWorker-after");
            };
      }
    }

    chain.run();

    // First plugin should wrap all others
    assertEquals(
        Arrays.asList(
            "A-startWorker-before",
            "B-startWorker-before",
            "C-startWorker-before",
            "worker-start",
            "C-startWorker-after",
            "B-startWorker-after",
            "A-startWorker-after"),
        order);
  }

  @Test
  public void testShutdownWorkerReverseOrder() {
    List<String> order = new ArrayList<>();

    SimplePlugin pluginA = createWorkerLifecycleTrackingPlugin("A", order);
    SimplePlugin pluginB = createWorkerLifecycleTrackingPlugin("B", order);
    SimplePlugin pluginC = createWorkerLifecycleTrackingPlugin("C", order);

    List<Object> plugins = Arrays.asList(pluginA, pluginB, pluginC);

    // Build chain in reverse (like WorkerFactory does)
    Runnable chain = () -> order.add("worker-shutdown");

    List<Object> reversed = new ArrayList<>(plugins);
    java.util.Collections.reverse(reversed);
    for (Object plugin : reversed) {
      if (plugin instanceof io.temporal.worker.WorkerPlugin) {
        final Runnable next = chain;
        final io.temporal.worker.WorkerPlugin workerPlugin =
            (io.temporal.worker.WorkerPlugin) plugin;
        chain =
            () -> {
              order.add(workerPlugin.getName() + "-shutdownWorker-before");
              workerPlugin.shutdownWorker("test-queue", null, next);
              order.add(workerPlugin.getName() + "-shutdownWorker-after");
            };
      }
    }

    chain.run();

    // First plugin should wrap all others
    assertEquals(
        Arrays.asList(
            "A-shutdownWorker-before",
            "B-shutdownWorker-before",
            "C-shutdownWorker-before",
            "worker-shutdown",
            "C-shutdownWorker-after",
            "B-shutdownWorker-after",
            "A-shutdownWorker-after"),
        order);
  }

  @Test
  public void testSimplePluginImplementsBothInterfaces() {
    SimplePlugin plugin = new SimplePlugin("dual-plugin") {};

    assertTrue(
        "SimplePlugin should implement ClientPlugin",
        plugin instanceof io.temporal.client.ClientPlugin);
    assertTrue(
        "SimplePlugin should implement WorkerPlugin",
        plugin instanceof io.temporal.worker.WorkerPlugin);
  }

  private SimplePlugin createTrackingPlugin(String name, List<String> order) {
    return new SimplePlugin(name) {
      @Override
      public WorkflowClientOptions.Builder configureClient(WorkflowClientOptions.Builder builder) {
        order.add(name + "-config");
        return builder;
      }
    };
  }

  private SimplePlugin createExecutionTrackingPlugin(String name, List<String> order) {
    return new SimplePlugin(name) {
      @Override
      public void startWorkerFactory(io.temporal.worker.WorkerFactory factory, Runnable next) {
        next.run();
      }
    };
  }

  private SimplePlugin createWorkerLifecycleTrackingPlugin(String name, List<String> order) {
    return new SimplePlugin(name) {
      @Override
      public void startWorker(String taskQueue, io.temporal.worker.Worker worker, Runnable next) {
        next.run();
      }

      @Override
      public void shutdownWorker(
          String taskQueue, io.temporal.worker.Worker worker, Runnable next) {
        next.run();
      }
    };
  }
}
