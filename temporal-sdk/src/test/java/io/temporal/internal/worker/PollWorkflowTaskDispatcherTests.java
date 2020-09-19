/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.worker;

import static junit.framework.TestCase.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PollWorkflowTaskDispatcherTests {

  LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
  ch.qos.logback.classic.Logger logger = context.getLogger(Logger.ROOT_LOGGER_NAME);

  private TestWorkflowService testService;
  private WorkflowServiceStubs service;
  private final Scope metricsScope = new NoopScope();

  @Before
  public void setUp() {
    testService = new TestWorkflowService();
    service = testService.newClientStub();
  }

  @After
  public void tearDown() {
    service.shutdownNow();
    try {
      service.awaitTermination(1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
    testService.close();
  }

  @Test
  public void pollWorkflowTasksAreDispatchedBasedOnTaskQueueName() {
    // Arrange
    AtomicBoolean handled = new AtomicBoolean(false);
    Functions.Proc1<PollWorkflowTaskQueueResponse> handler = r -> handled.set(true);

    PollWorkflowTaskDispatcher dispatcher = new PollWorkflowTaskDispatcher(service, metricsScope);
    dispatcher.subscribe("taskqueue1", handler);

    // Act
    PollWorkflowTaskQueueResponse response = CreatePollWorkflowTaskQueueResponse("taskqueue1");
    dispatcher.process(response);

    // Assert
    assertTrue(handled.get());
  }

  @Test
  public void pollWorkflowTasksAreDispatchedToTheCorrectHandler() {

    // Arrange
    AtomicBoolean handled = new AtomicBoolean(false);
    AtomicBoolean handled2 = new AtomicBoolean(false);

    Functions.Proc1<PollWorkflowTaskQueueResponse> handler = r -> handled.set(true);
    Functions.Proc1<PollWorkflowTaskQueueResponse> handler2 = r -> handled2.set(true);

    PollWorkflowTaskDispatcher dispatcher = new PollWorkflowTaskDispatcher(service, metricsScope);
    dispatcher.subscribe("taskqueue1", handler);
    dispatcher.subscribe("taskqueue2", handler2);

    // Act
    PollWorkflowTaskQueueResponse response = CreatePollWorkflowTaskQueueResponse("taskqueue1");
    dispatcher.process(response);

    // Assert
    assertTrue(handled.get());
    assertFalse(handled2.get());
  }

  @Test
  public void handlersGetOverwrittenWhenRegisteredForTheSameTaskQueue() {

    // Arrange
    AtomicBoolean handled = new AtomicBoolean(false);
    AtomicBoolean handled2 = new AtomicBoolean(false);

    Functions.Proc1<PollWorkflowTaskQueueResponse> handler = r -> handled.set(true);
    Functions.Proc1<PollWorkflowTaskQueueResponse> handler2 = r -> handled2.set(true);

    PollWorkflowTaskDispatcher dispatcher = new PollWorkflowTaskDispatcher(service, metricsScope);
    dispatcher.subscribe("taskqueue1", handler);
    dispatcher.subscribe("taskqueue1", handler2);

    // Act
    PollWorkflowTaskQueueResponse response = CreatePollWorkflowTaskQueueResponse("taskqueue1");
    dispatcher.process(response);

    // Assert
    assertTrue(handled2.get());
    assertFalse(handled.get());
  }

  @Test
  @Ignore // TODO: Rewrite as mocking of WorkflowServiceBlockingStub is not possible
  public void aWarningIsLoggedAndWorkflowTaskIsFailedWhenNoHandlerIsRegisteredForTheTaskQueue()
      throws Exception {

    // Arrange
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);

    AtomicBoolean handled = new AtomicBoolean(false);
    Functions.Proc1<PollWorkflowTaskQueueResponse> handler = r -> handled.set(true);

    WorkflowServiceGrpc.WorkflowServiceBlockingStub stub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    WorkflowServiceStubs mockService = mock(WorkflowServiceStubs.class);
    when(mockService.blockingStub()).thenReturn(stub);

    PollWorkflowTaskDispatcher dispatcher =
        new PollWorkflowTaskDispatcher(mockService, metricsScope);
    dispatcher.subscribe("taskqueue1", handler);

    // Act
    PollWorkflowTaskQueueResponse response =
        CreatePollWorkflowTaskQueueResponse("I Don't Exist TaskQueue");
    dispatcher.process(response);

    // Assert
    verify(stub, times(1)).respondWorkflowTaskFailed(any());
    assertFalse(handled.get());
    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertEquals(
        String.format(
            "No handler is subscribed for the PollWorkflowTaskQueueResponse.WorkflowExecutionTaskQueue %s",
            "I Don't Exist TaskQueue"),
        event.getFormattedMessage());
  }

  private PollWorkflowTaskQueueResponse CreatePollWorkflowTaskQueueResponse(String taskQueueName) {
    return PollWorkflowTaskQueueResponse.newBuilder()
        .setWorkflowExecutionTaskQueue(TaskQueue.newBuilder().setName(taskQueueName).build())
        .build();
  }
}
