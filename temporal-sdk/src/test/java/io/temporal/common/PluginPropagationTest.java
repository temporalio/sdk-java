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
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkerPlugin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;
import org.junit.Test;

/**
 * Tests that plugins propagate through the full chain: WorkflowServiceStubsOptions →
 * WorkflowClientOptions → WorkerFactory
 */
public class PluginPropagationTest {

  /** A plugin that tracks all configuration calls via subclassing. */
  private static class TrackingPlugin extends SimplePlugin {
    private final List<String> callLog;

    TrackingPlugin(String name, List<String> callLog) {
      super(name);
      this.callLog = callLog;
    }

    @Override
    public void configureServiceStubs(@Nonnull WorkflowServiceStubsOptions.Builder builder) {
      callLog.add("configureServiceStubs");
    }

    @Override
    public void configureWorkflowClient(@Nonnull WorkflowClientOptions.Builder builder) {
      super.configureWorkflowClient(builder);
      callLog.add("configureWorkflowClient");
    }

    @Override
    public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
      super.configureWorkerFactory(builder);
      callLog.add("configureWorkerFactory");
    }

    @Override
    public void configureWorker(@Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder) {
      callLog.add("configureWorker");
    }
  }

  @Test
  public void testPluginPropagatesFromServiceStubsToWorkerFactory() {
    List<String> callLog = new ArrayList<>();

    // Create a plugin that tracks all configuration calls
    TrackingPlugin trackingPlugin = new TrackingPlugin("tracking-plugin", callLog);

    // Set the plugin ONLY on WorkflowServiceStubsOptions
    WorkflowServiceStubsOptions stubsOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .setPlugins((io.temporal.serviceclient.WorkflowServiceStubsPlugin) trackingPlugin)
            .build();

    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder().setWorkflowServiceStubsOptions(stubsOptions).build();

    // Create the test environment - this triggers the full propagation chain
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(testOptions);
    try {
      // Create a worker to trigger configureWorker
      env.newWorker("test-task-queue");

      // Verify the plugin was called at each level
      assertTrue(
          "configureServiceStubs should be called", callLog.contains("configureServiceStubs"));
      assertTrue(
          "configureWorkflowClient should be called (propagated from service stubs)",
          callLog.contains("configureWorkflowClient"));
      assertTrue(
          "configureWorkerFactory should be called (propagated from client)",
          callLog.contains("configureWorkerFactory"));
      assertTrue(
          "configureWorker should be called (propagated from client)",
          callLog.contains("configureWorker"));

      // Verify the order: service stubs -> client -> worker factory -> worker
      assertEquals(
          "Configuration should happen in correct order",
          Arrays.asList(
              "configureServiceStubs",
              "configureWorkflowClient",
              "configureWorkerFactory",
              "configureWorker"),
          callLog);
    } finally {
      env.close();
    }
  }

  @Test
  public void testPluginSetOnClientOnlyDoesNotAffectServiceStubs() {
    List<String> callLog = new ArrayList<>();

    // Create a plugin that tracks all configuration calls
    TrackingPlugin trackingPlugin = new TrackingPlugin("tracking-plugin", callLog);

    // Set the plugin ONLY on WorkflowClientOptions (not service stubs)
    WorkflowClientOptions clientOptions =
        WorkflowClientOptions.newBuilder()
            .setPlugins((io.temporal.client.WorkflowClientPlugin) trackingPlugin)
            .build();

    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(clientOptions).build();

    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(testOptions);
    try {
      env.newWorker("test-task-queue");

      // configureServiceStubs should NOT be called (plugin wasn't set there)
      assertFalse(
          "configureServiceStubs should NOT be called", callLog.contains("configureServiceStubs"));

      // But client and worker factory should be called
      assertTrue(
          "configureWorkflowClient should be called", callLog.contains("configureWorkflowClient"));
      assertTrue(
          "configureWorkerFactory should be called", callLog.contains("configureWorkerFactory"));
    } finally {
      env.close();
    }
  }

  /** A plugin that only logs workflow client configuration. */
  private static class ClientOnlyTrackingPlugin extends SimplePlugin {
    private final List<String> callLog;
    private final String logPrefix;

    ClientOnlyTrackingPlugin(String name, List<String> callLog, String logPrefix) {
      super(name);
      this.callLog = callLog;
      this.logPrefix = logPrefix;
    }

    @Override
    public void configureWorkflowClient(@Nonnull WorkflowClientOptions.Builder builder) {
      super.configureWorkflowClient(builder);
      callLog.add(logPrefix + "-configureWorkflowClient");
    }
  }

  @Test
  public void testMergedPluginsFromBothLevels() {
    List<String> callLog = new ArrayList<>();

    // Plugin set on service stubs
    ClientOnlyTrackingPlugin stubsPlugin =
        new ClientOnlyTrackingPlugin("stubs-plugin", callLog, "stubs-plugin");

    // Different plugin set on client
    ClientOnlyTrackingPlugin clientPlugin =
        new ClientOnlyTrackingPlugin("client-plugin", callLog, "client-plugin");

    WorkflowServiceStubsOptions stubsOptions =
        WorkflowServiceStubsOptions.newBuilder()
            .setPlugins((io.temporal.serviceclient.WorkflowServiceStubsPlugin) stubsPlugin)
            .build();

    WorkflowClientOptions clientOptions =
        WorkflowClientOptions.newBuilder()
            .setPlugins((io.temporal.client.WorkflowClientPlugin) clientPlugin)
            .build();

    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowServiceStubsOptions(stubsOptions)
            .setWorkflowClientOptions(clientOptions)
            .build();

    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(testOptions);
    try {
      // Both plugins should have their configureWorkflowClient called
      // Propagated plugins come first, then explicit client plugins
      assertEquals(
          "Both plugins should be called in correct order",
          Arrays.asList(
              "stubs-plugin-configureWorkflowClient", "client-plugin-configureWorkflowClient"),
          callLog);
    } finally {
      env.close();
    }
  }

  /** A plugin that only logs worker factory and worker configuration. */
  private static class WorkerOnlyTrackingPlugin extends SimplePlugin {
    private final List<String> callLog;
    private final String logPrefix;

    WorkerOnlyTrackingPlugin(String name, List<String> callLog, String logPrefix) {
      super(name);
      this.callLog = callLog;
      this.logPrefix = logPrefix;
    }

    @Override
    public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
      super.configureWorkerFactory(builder);
      callLog.add(logPrefix + "-configureWorkerFactory");
    }

    @Override
    public void configureWorker(@Nonnull String taskQueue, @Nonnull WorkerOptions.Builder builder) {
      callLog.add(logPrefix + "-configureWorker");
    }
  }

  @Test
  public void testWorkerOnlyPluginOnFactoryOptions() {
    List<String> callLog = new ArrayList<>();

    // Create a plugin that only uses worker-level customization
    WorkerOnlyTrackingPlugin workerOnlyPlugin =
        new WorkerOnlyTrackingPlugin("worker-only-plugin", callLog, "worker-only");

    // Set the plugin on WorkerFactoryOptions (not on client)
    io.temporal.worker.WorkerFactoryOptions factoryOptions =
        io.temporal.worker.WorkerFactoryOptions.newBuilder()
            .setPlugins((WorkerPlugin) workerOnlyPlugin)
            .build();

    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder().setWorkerFactoryOptions(factoryOptions).build();

    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(testOptions);
    try {
      env.newWorker("test-task-queue");

      // Worker-only plugin should have its methods called
      assertTrue(
          "configureWorkerFactory should be called",
          callLog.contains("worker-only-configureWorkerFactory"));
      assertTrue(
          "configureWorker should be called", callLog.contains("worker-only-configureWorker"));
    } finally {
      env.close();
    }
  }

  /** A plugin that only logs worker factory configuration. */
  private static class FactoryOnlyTrackingPlugin extends SimplePlugin {
    private final List<String> callLog;
    private final String logPrefix;

    FactoryOnlyTrackingPlugin(String name, List<String> callLog, String logPrefix) {
      super(name);
      this.callLog = callLog;
      this.logPrefix = logPrefix;
    }

    @Override
    public void configureWorkerFactory(@Nonnull WorkerFactoryOptions.Builder builder) {
      super.configureWorkerFactory(builder);
      callLog.add(logPrefix + "-configureWorkerFactory");
    }
  }

  @Test
  public void testMergedPluginsAtWorkerFactoryLevel() {
    List<String> callLog = new ArrayList<>();

    // Plugin propagated from client
    FactoryOnlyTrackingPlugin clientPlugin =
        new FactoryOnlyTrackingPlugin("client-plugin", callLog, "client-plugin");

    // Plugin set directly on factory options
    FactoryOnlyTrackingPlugin factoryPlugin =
        new FactoryOnlyTrackingPlugin("factory-plugin", callLog, "factory-plugin");

    WorkflowClientOptions clientOptions =
        WorkflowClientOptions.newBuilder()
            .setPlugins((io.temporal.client.WorkflowClientPlugin) clientPlugin)
            .build();

    io.temporal.worker.WorkerFactoryOptions factoryOptions =
        io.temporal.worker.WorkerFactoryOptions.newBuilder()
            .setPlugins((WorkerPlugin) factoryPlugin)
            .build();

    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setWorkflowClientOptions(clientOptions)
            .setWorkerFactoryOptions(factoryOptions)
            .build();

    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(testOptions);
    try {
      // Both plugins should be called - propagated first, then explicit
      assertEquals(
          "Both plugins should be called in correct order",
          Arrays.asList(
              "client-plugin-configureWorkerFactory", "factory-plugin-configureWorkerFactory"),
          callLog);
    } finally {
      env.close();
    }
  }
}
