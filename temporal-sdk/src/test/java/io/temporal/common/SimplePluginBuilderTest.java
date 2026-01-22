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
import static org.mockito.Mockito.*;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.common.interceptors.WorkflowClientInterceptorBase;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkerPlugin;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class SimplePluginBuilderTest {

  @Test
  public void testSimplePluginName() {
    SimplePlugin plugin = SimplePlugin.newBuilder("test-plugin").build();
    assertEquals("test-plugin", plugin.getName());
  }

  @Test
  public void testSimplePluginImplementsAllInterfaces() {
    SimplePlugin plugin = SimplePlugin.newBuilder("test").build();
    assertTrue(
        "Should implement WorkflowServiceStubsPlugin",
        plugin instanceof io.temporal.serviceclient.WorkflowServiceStubsPlugin);
    assertTrue(
        "Should implement WorkflowClientPlugin",
        plugin instanceof io.temporal.client.WorkflowClientPlugin);
    assertTrue("Should implement WorkerPlugin", plugin instanceof io.temporal.worker.WorkerPlugin);
  }

  @Test
  public void testCustomizeServiceStubs() {
    AtomicBoolean customized = new AtomicBoolean(false);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .customizeServiceStubs(
                builder -> {
                  customized.set(true);
                })
            .build();

    WorkflowServiceStubsOptions.Builder builder = WorkflowServiceStubsOptions.newBuilder();
    ((io.temporal.serviceclient.WorkflowServiceStubsPlugin) plugin).configureServiceStubs(builder);

    assertTrue("Customizer should have been called", customized.get());
  }

  @Test
  public void testCustomizeWorkflowClient() {
    AtomicBoolean customized = new AtomicBoolean(false);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .customizeWorkflowClient(
                builder -> {
                  customized.set(true);
                  builder.setIdentity("custom-identity");
                })
            .build();

    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    ((io.temporal.client.WorkflowClientPlugin) plugin).configureWorkflowClient(builder);

    assertTrue("Customizer should have been called", customized.get());
    assertEquals("custom-identity", builder.build().getIdentity());
  }

  @Test
  public void testCustomizeWorkerFactory() {
    AtomicBoolean customized = new AtomicBoolean(false);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .customizeWorkerFactory(
                builder -> {
                  customized.set(true);
                  builder.setWorkflowCacheSize(100);
                })
            .build();

    WorkerFactoryOptions.Builder builder = WorkerFactoryOptions.newBuilder();
    ((io.temporal.worker.WorkerPlugin) plugin).configureWorkerFactory(builder);

    assertTrue("Customizer should have been called", customized.get());
    assertEquals(100, builder.build().getWorkflowCacheSize());
  }

  @Test
  public void testCustomizeWorker() {
    AtomicBoolean customized = new AtomicBoolean(false);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .customizeWorker(
                builder -> {
                  customized.set(true);
                  builder.setMaxConcurrentActivityExecutionSize(50);
                })
            .build();

    WorkerOptions.Builder builder = WorkerOptions.newBuilder();
    ((io.temporal.worker.WorkerPlugin) plugin).configureWorker("test-queue", builder);

    assertTrue("Customizer should have been called", customized.get());
    assertEquals(50, builder.build().getMaxConcurrentActivityExecutionSize());
  }

  @Test
  public void testMultipleCustomizers() {
    AtomicInteger callCount = new AtomicInteger(0);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .customizeWorkflowClient(builder -> callCount.incrementAndGet())
            .customizeWorkflowClient(builder -> callCount.incrementAndGet())
            .customizeWorkflowClient(builder -> callCount.incrementAndGet())
            .build();

    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    ((io.temporal.client.WorkflowClientPlugin) plugin).configureWorkflowClient(builder);

    assertEquals("All customizers should be called", 3, callCount.get());
  }

  @Test
  public void testAddWorkerInterceptors() {
    WorkerInterceptor interceptor = new WorkerInterceptorBase() {};

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test").addWorkerInterceptors(interceptor).build();

    WorkerFactoryOptions.Builder builder = WorkerFactoryOptions.newBuilder();
    ((io.temporal.worker.WorkerPlugin) plugin).configureWorkerFactory(builder);

    WorkerInterceptor[] interceptors = builder.build().getWorkerInterceptors();
    assertEquals(1, interceptors.length);
    assertSame(interceptor, interceptors[0]);
  }

  @Test
  public void testAddClientInterceptors() {
    WorkflowClientInterceptor interceptor = new WorkflowClientInterceptorBase() {};

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test").addClientInterceptors(interceptor).build();

    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    ((io.temporal.client.WorkflowClientPlugin) plugin).configureWorkflowClient(builder);

    WorkflowClientInterceptor[] interceptors = builder.build().getInterceptors();
    assertEquals(1, interceptors.length);
    assertSame(interceptor, interceptors[0]);
  }

  @Test
  public void testInterceptorsAppendToExisting() {
    WorkerInterceptor existingInterceptor = new WorkerInterceptorBase() {};
    WorkerInterceptor newInterceptor = new WorkerInterceptorBase() {};

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test").addWorkerInterceptors(newInterceptor).build();

    WorkerFactoryOptions.Builder builder =
        WorkerFactoryOptions.newBuilder().setWorkerInterceptors(existingInterceptor);
    ((io.temporal.worker.WorkerPlugin) plugin).configureWorkerFactory(builder);

    WorkerInterceptor[] interceptors = builder.build().getWorkerInterceptors();
    assertEquals(2, interceptors.length);
    assertSame(existingInterceptor, interceptors[0]);
    assertSame(newInterceptor, interceptors[1]);
  }

  @Test
  public void testInitializeWorker() {
    AtomicBoolean initialized = new AtomicBoolean(false);
    String[] capturedTaskQueue = {null};

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .initializeWorker(
                (taskQueue, worker) -> {
                  initialized.set(true);
                  capturedTaskQueue[0] = taskQueue;
                })
            .build();

    // Call initializeWorker with null worker (we're just testing the callback is invoked)
    ((io.temporal.worker.WorkerPlugin) plugin).initializeWorker("my-task-queue", null);

    assertTrue("Initializer should have been called", initialized.get());
    assertEquals("my-task-queue", capturedTaskQueue[0]);
  }

  @Test
  public void testMultipleWorkerInitializers() {
    AtomicInteger callCount = new AtomicInteger(0);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .initializeWorker((taskQueue, worker) -> callCount.incrementAndGet())
            .initializeWorker((taskQueue, worker) -> callCount.incrementAndGet())
            .initializeWorker((taskQueue, worker) -> callCount.incrementAndGet())
            .build();

    ((io.temporal.worker.WorkerPlugin) plugin).initializeWorker("test-queue", null);

    assertEquals("All initializers should be called", 3, callCount.get());
  }

  @Test(expected = NullPointerException.class)
  public void testNullInitializeWorker() {
    SimplePlugin.newBuilder("test").initializeWorker(null);
  }

  @Test
  public void testOnWorkerStart() throws Exception {
    AtomicBoolean started = new AtomicBoolean(false);
    String[] capturedTaskQueue = {null};

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onWorkerStart(
                (taskQueue, worker) -> {
                  started.set(true);
                  capturedTaskQueue[0] = taskQueue;
                })
            .build();

    AtomicBoolean nextCalled = new AtomicBoolean(false);
    ((io.temporal.worker.WorkerPlugin) plugin)
        .startWorker("my-task-queue", null, (tq, w) -> nextCalled.set(true));

    assertTrue("next should be called", nextCalled.get());
    assertTrue("Callback should have been called", started.get());
    assertEquals("my-task-queue", capturedTaskQueue[0]);
  }

  @Test
  public void testOnWorkerShutdown() {
    AtomicBoolean shutdown = new AtomicBoolean(false);
    String[] capturedTaskQueue = {null};

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onWorkerShutdown(
                (taskQueue, worker) -> {
                  shutdown.set(true);
                  capturedTaskQueue[0] = taskQueue;
                })
            .build();

    AtomicBoolean nextCalled = new AtomicBoolean(false);
    ((io.temporal.worker.WorkerPlugin) plugin)
        .shutdownWorker("my-task-queue", null, (tq, w) -> nextCalled.set(true));

    assertTrue("next should be called", nextCalled.get());
    assertTrue("Callback should have been called", shutdown.get());
    assertEquals("my-task-queue", capturedTaskQueue[0]);
  }

  @Test
  public void testMultipleOnWorkerStartCallbacks() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onWorkerStart((taskQueue, worker) -> callCount.incrementAndGet())
            .onWorkerStart((taskQueue, worker) -> callCount.incrementAndGet())
            .onWorkerStart((taskQueue, worker) -> callCount.incrementAndGet())
            .build();

    ((io.temporal.worker.WorkerPlugin) plugin).startWorker("test-queue", null, (tq, w) -> {});

    assertEquals("All callbacks should be called", 3, callCount.get());
  }

  @Test
  public void testMultipleOnWorkerShutdownCallbacks() {
    AtomicInteger callCount = new AtomicInteger(0);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onWorkerShutdown((taskQueue, worker) -> callCount.incrementAndGet())
            .onWorkerShutdown((taskQueue, worker) -> callCount.incrementAndGet())
            .onWorkerShutdown((taskQueue, worker) -> callCount.incrementAndGet())
            .build();

    ((io.temporal.worker.WorkerPlugin) plugin).shutdownWorker("test-queue", null, (tq, w) -> {});

    assertEquals("All callbacks should be called", 3, callCount.get());
  }

  @Test
  public void testOnWorkerFactoryStart() throws Exception {
    AtomicBoolean started = new AtomicBoolean(false);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test").onWorkerFactoryStart(factory -> started.set(true)).build();

    AtomicBoolean nextCalled = new AtomicBoolean(false);
    ((io.temporal.worker.WorkerPlugin) plugin)
        .startWorkerFactory(null, (f) -> nextCalled.set(true));

    assertTrue("next should be called", nextCalled.get());
    assertTrue("Callback should have been called", started.get());
  }

  @Test
  public void testOnWorkerFactoryShutdown() throws Exception {
    AtomicBoolean shutdown = new AtomicBoolean(false);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onWorkerFactoryShutdown(factory -> shutdown.set(true))
            .build();

    AtomicBoolean nextCalled = new AtomicBoolean(false);
    ((io.temporal.worker.WorkerPlugin) plugin)
        .shutdownWorkerFactory(null, (f) -> nextCalled.set(true));

    assertTrue("next should be called", nextCalled.get());
    assertTrue("Callback should have been called", shutdown.get());
  }

  @Test
  public void testMultipleOnWorkerFactoryStartCallbacks() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onWorkerFactoryStart(factory -> callCount.incrementAndGet())
            .onWorkerFactoryStart(factory -> callCount.incrementAndGet())
            .onWorkerFactoryStart(factory -> callCount.incrementAndGet())
            .build();

    ((io.temporal.worker.WorkerPlugin) plugin).startWorkerFactory(null, (f) -> {});

    assertEquals("All callbacks should be called", 3, callCount.get());
  }

  @Test
  public void testMultipleOnWorkerFactoryShutdownCallbacks() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onWorkerFactoryShutdown(factory -> callCount.incrementAndGet())
            .onWorkerFactoryShutdown(factory -> callCount.incrementAndGet())
            .onWorkerFactoryShutdown(factory -> callCount.incrementAndGet())
            .build();

    ((io.temporal.worker.WorkerPlugin) plugin).shutdownWorkerFactory(null, (f) -> {});

    assertEquals("All callbacks should be called", 3, callCount.get());
  }

  @Test(expected = NullPointerException.class)
  public void testNullOnWorkerStart() {
    SimplePlugin.newBuilder("test").onWorkerStart(null);
  }

  @Test(expected = NullPointerException.class)
  public void testNullOnWorkerShutdown() {
    SimplePlugin.newBuilder("test").onWorkerShutdown(null);
  }

  @Test(expected = NullPointerException.class)
  public void testNullOnWorkerFactoryStart() {
    SimplePlugin.newBuilder("test").onWorkerFactoryStart(null);
  }

  @Test(expected = NullPointerException.class)
  public void testNullOnWorkerFactoryShutdown() {
    SimplePlugin.newBuilder("test").onWorkerFactoryShutdown(null);
  }

  @Test(expected = NullPointerException.class)
  public void testNullName() {
    SimplePlugin.newBuilder(null);
  }

  @Test(expected = NullPointerException.class)
  public void testNullCustomizer() {
    SimplePlugin.newBuilder("test").customizeWorkflowClient(null);
  }

  @Test
  public void testSetDataConverter() {
    DataConverter customConverter = mock(DataConverter.class);

    SimplePlugin plugin = SimplePlugin.newBuilder("test").setDataConverter(customConverter).build();

    WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder();
    ((io.temporal.client.WorkflowClientPlugin) plugin).configureWorkflowClient(builder);

    assertSame(customConverter, builder.build().getDataConverter());
  }

  @Test(expected = NullPointerException.class)
  public void testNullDataConverter() {
    SimplePlugin.newBuilder("test").setDataConverter(null);
  }

  @Test
  public void testRegisterWorkflowImplementationTypes() {
    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .registerWorkflowImplementationTypes(String.class, Integer.class)
            .build();

    Worker mockWorker = mock(Worker.class);
    ((io.temporal.worker.WorkerPlugin) plugin).initializeWorker("test-queue", mockWorker);

    verify(mockWorker).registerWorkflowImplementationTypes(String.class, Integer.class);
  }

  @Test
  public void testRegisterActivitiesImplementations() {
    Object activity1 = new Object();
    Object activity2 = new Object();

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .registerActivitiesImplementations(activity1, activity2)
            .build();

    Worker mockWorker = mock(Worker.class);
    ((io.temporal.worker.WorkerPlugin) plugin).initializeWorker("test-queue", mockWorker);

    verify(mockWorker).registerActivitiesImplementations(activity1, activity2);
  }

  @Test
  public void testRegisterNexusServiceImplementation() {
    Object nexusService = new Object();

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test").registerNexusServiceImplementation(nexusService).build();

    Worker mockWorker = mock(Worker.class);
    ((io.temporal.worker.WorkerPlugin) plugin).initializeWorker("test-queue", mockWorker);

    verify(mockWorker).registerNexusServiceImplementation(nexusService);
  }

  @Test
  public void testRegisterMultipleNexusServiceImplementations() {
    Object nexusService1 = new Object();
    Object nexusService2 = new Object();

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .registerNexusServiceImplementation(nexusService1)
            .registerNexusServiceImplementation(nexusService2)
            .build();

    Worker mockWorker = mock(Worker.class);
    ((io.temporal.worker.WorkerPlugin) plugin).initializeWorker("test-queue", mockWorker);

    verify(mockWorker).registerNexusServiceImplementation(nexusService1);
    verify(mockWorker).registerNexusServiceImplementation(nexusService2);
  }

  @Test(expected = NullPointerException.class)
  public void testNullNexusServiceImplementation() {
    SimplePlugin.newBuilder("test").registerNexusServiceImplementation(null);
  }

  @Test
  public void testRegistrationsWithCustomInitializer() {
    AtomicBoolean customInitializerCalled = new AtomicBoolean(false);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .registerWorkflowImplementationTypes(String.class)
            .registerActivitiesImplementations(new Object())
            .initializeWorker((taskQueue, worker) -> customInitializerCalled.set(true))
            .build();

    Worker mockWorker = mock(Worker.class);
    ((io.temporal.worker.WorkerPlugin) plugin).initializeWorker("test-queue", mockWorker);

    // Verify registrations happen before custom initializer
    verify(mockWorker).registerWorkflowImplementationTypes(String.class);
    verify(mockWorker).registerActivitiesImplementations(any());
    assertTrue(
        "Custom initializer should be called after registrations", customInitializerCalled.get());
  }

  // ==================== Replay Tests ====================

  @Test
  public void testOnReplayWorkflowExecution() throws Exception {
    AtomicBoolean callbackCalled = new AtomicBoolean(false);
    AtomicReference<Worker> capturedWorker = new AtomicReference<>();
    AtomicReference<WorkflowExecutionHistory> capturedHistory = new AtomicReference<>();

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onReplayWorkflowExecution(
                (worker, history) -> {
                  callbackCalled.set(true);
                  capturedWorker.set(worker);
                  capturedHistory.set(history);
                })
            .build();

    Worker mockWorker = mock(Worker.class);
    WorkflowExecutionHistory mockHistory = mock(WorkflowExecutionHistory.class);
    AtomicBoolean nextCalled = new AtomicBoolean(false);

    ((WorkerPlugin) plugin)
        .replayWorkflowExecution(mockWorker, mockHistory, (w, h) -> nextCalled.set(true));

    assertTrue("next should be called", nextCalled.get());
    assertTrue("Callback should have been called", callbackCalled.get());
    assertSame(mockWorker, capturedWorker.get());
    assertSame(mockHistory, capturedHistory.get());
  }

  @Test
  public void testMultipleOnReplayWorkflowExecutionCallbacks() throws Exception {
    AtomicInteger callCount = new AtomicInteger(0);

    SimplePlugin plugin =
        SimplePlugin.newBuilder("test")
            .onReplayWorkflowExecution((worker, history) -> callCount.incrementAndGet())
            .onReplayWorkflowExecution((worker, history) -> callCount.incrementAndGet())
            .onReplayWorkflowExecution((worker, history) -> callCount.incrementAndGet())
            .build();

    Worker mockWorker = mock(Worker.class);
    WorkflowExecutionHistory mockHistory = mock(WorkflowExecutionHistory.class);

    ((WorkerPlugin) plugin).replayWorkflowExecution(mockWorker, mockHistory, (w, h) -> {});

    assertEquals("All callbacks should be called", 3, callCount.get());
  }

  @Test(expected = NullPointerException.class)
  public void testNullOnReplayWorkflowExecution() {
    SimplePlugin.newBuilder("test").onReplayWorkflowExecution(null);
  }
}
