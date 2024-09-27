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

package io.temporal.internal.nexus;

import static org.mockito.Mockito.mock;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.util.Duration;
import io.nexusrpc.Header;
import io.nexusrpc.OperationInfo;
import io.nexusrpc.OperationStillRunningException;
import io.nexusrpc.handler.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Request;
import io.temporal.api.nexus.v1.StartOperationRequest;
import io.temporal.api.workflowservice.v1.PollNexusTaskQueueResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.WorkerInterceptor;
import io.temporal.common.reporter.TestStatsReporter;
import io.temporal.internal.worker.NexusTask;
import io.temporal.internal.worker.NexusTaskHandler;
import io.temporal.workflow.shared.TestNexusServices;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class NexusTaskHandlerImplTest {
  static final String NAMESPACE = "testNamespace";
  static final String TASK_QUEUE = "testTaskQueue";
  static final DataConverter dataConverter = DefaultDataConverter.STANDARD_INSTANCE;
  private Scope metricsScope;
  private TestStatsReporter reporter;

  @Before
  public void setUp() {
    reporter = new TestStatsReporter();
    metricsScope = new RootScopeBuilder().reporter(reporter).reportEvery(Duration.ofMillis(10));
  }

  @Test
  public void nexusTaskHandlerImplStartNoService() {
    WorkflowClient client = mock(WorkflowClient.class);
    NexusTaskHandlerImpl nexusTaskHandlerImpl =
        new NexusTaskHandlerImpl(
            client, NAMESPACE, TASK_QUEUE, dataConverter, new WorkerInterceptor[] {});
    // Verify if no service is registered, start should return false
    Assert.assertFalse(nexusTaskHandlerImpl.start());
  }

  @Test
  public void nexusTaskHandlerImplStart() {
    WorkflowClient client = mock(WorkflowClient.class);
    NexusTaskHandlerImpl nexusTaskHandlerImpl =
        new NexusTaskHandlerImpl(
            client, NAMESPACE, TASK_QUEUE, dataConverter, new WorkerInterceptor[] {});
    nexusTaskHandlerImpl.registerNexusServiceImplementations(
        new Object[] {new TestNexusServiceImpl()});
    // Verify if any services are registered, start should return true
    Assert.assertTrue(nexusTaskHandlerImpl.start());
  }

  @Test
  public void startSyncTask() throws TimeoutException {
    WorkflowClient client = mock(WorkflowClient.class);
    NexusTaskHandlerImpl nexusTaskHandlerImpl =
        new NexusTaskHandlerImpl(
            client, NAMESPACE, TASK_QUEUE, dataConverter, new WorkerInterceptor[] {});
    nexusTaskHandlerImpl.registerNexusServiceImplementations(
        new Object[] {new TestNexusServiceImpl()});
    nexusTaskHandlerImpl.start();

    Payload originalPayload = dataConverter.toPayload("world").get();
    PollNexusTaskQueueResponse.Builder task =
        PollNexusTaskQueueResponse.newBuilder()
            .setRequest(
                Request.newBuilder()
                    .setStartOperation(
                        StartOperationRequest.newBuilder()
                            .setOperation("operation")
                            .setService("TestNexusService1")
                            // Passing bytes that are not valid UTF-8 to make sure this does not
                            // error out
                            .setPayload(
                                Payload.newBuilder(originalPayload)
                                    .putMetadata(
                                        "ByteKey", ByteString.copyFrom("\\xc3\\x28".getBytes()))
                                    .build())
                            .build()));

    NexusTaskHandler.Result result =
        nexusTaskHandlerImpl.handle(new NexusTask(task, null, null), metricsScope);
    Assert.assertNull(result.getHandlerError());
    Assert.assertNotNull(result.getResponse());
    Assert.assertEquals(
        "Hello, world!",
        dataConverter.fromPayload(
            result.getResponse().getStartOperation().getSyncSuccess().getPayload(),
            String.class,
            String.class));
  }

  @Test
  public void syncTimeoutTask() {
    WorkflowClient client = mock(WorkflowClient.class);
    NexusTaskHandlerImpl nexusTaskHandlerImpl =
        new NexusTaskHandlerImpl(
            client, NAMESPACE, TASK_QUEUE, dataConverter, new WorkerInterceptor[] {});
    nexusTaskHandlerImpl.registerNexusServiceImplementations(
        new Object[] {new TestNexusServiceImpl2()});
    nexusTaskHandlerImpl.start();

    PollNexusTaskQueueResponse.Builder task =
        PollNexusTaskQueueResponse.newBuilder()
            .setRequest(
                Request.newBuilder()
                    .putHeader(Header.REQUEST_TIMEOUT, "100ms")
                    .setStartOperation(
                        StartOperationRequest.newBuilder()
                            .setOperation("operation")
                            .setService("TestNexusService2")
                            .setPayload(dataConverter.toPayload(1).get())
                            .build()));

    Assert.assertThrows(
        TimeoutException.class,
        () -> nexusTaskHandlerImpl.handle(new NexusTask(task, null, null), metricsScope));
  }

  @Test
  public void startAsyncSyncOperation() throws TimeoutException {
    WorkflowClient client = mock(WorkflowClient.class);
    NexusTaskHandlerImpl nexusTaskHandlerImpl =
        new NexusTaskHandlerImpl(
            client, NAMESPACE, TASK_QUEUE, dataConverter, new WorkerInterceptor[] {});
    nexusTaskHandlerImpl.registerNexusServiceImplementations(
        new Object[] {new TestNexusServiceImplAsync()});
    nexusTaskHandlerImpl.start();

    PollNexusTaskQueueResponse.Builder task =
        PollNexusTaskQueueResponse.newBuilder()
            .setRequest(
                Request.newBuilder()
                    .setStartOperation(
                        StartOperationRequest.newBuilder()
                            .setOperation("operation")
                            .setService("TestNexusService1")
                            .setPayload(dataConverter.toPayload("test id").get())
                            .build()));

    NexusTaskHandler.Result result =
        nexusTaskHandlerImpl.handle(new NexusTask(task, null, null), metricsScope);
    Assert.assertNull(result.getHandlerError());
    Assert.assertNotNull(result.getResponse());
    Assert.assertEquals(
        "test id", result.getResponse().getStartOperation().getAsyncSuccess().getOperationId());
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService2.class)
  public class TestNexusServiceImpl2 {
    @OperationImpl
    public OperationHandler<Integer, Integer> operation() {
      // Implemented inline
      return OperationHandler.sync(
          (ctx, details, i) -> {
            while (!ctx.isMethodCancelled()) {
              try {
                Thread.sleep(1000 * i);
              } catch (InterruptedException e) {
                throw new RuntimeException(e);
              }
            }
            return i;
          });
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImplAsync {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented via handler
      return new AsyncHandle();
    }

    // Naive example showing async operations tracked in-memory
    private class AsyncHandle implements OperationHandler<String, String> {

      @Override
      public OperationStartResult<String> start(
          OperationContext context, OperationStartDetails details, @Nullable String id) {
        return OperationStartResult.async(id);
      }

      @Override
      public String fetchResult(OperationContext context, OperationFetchResultDetails details)
          throws OperationStillRunningException {
        throw new UnsupportedOperationException("Not implemented");
      }

      @Override
      public OperationInfo fetchInfo(OperationContext context, OperationFetchInfoDetails details) {
        throw new UnsupportedOperationException("Not implemented");
      }

      @Override
      public void cancel(OperationContext context, OperationCancelDetails details) {
        throw new UnsupportedOperationException("Not implemented");
      }
    }
  }
}
