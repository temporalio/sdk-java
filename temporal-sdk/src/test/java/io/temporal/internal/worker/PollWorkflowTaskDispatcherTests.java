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

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Functions;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PollWorkflowTaskDispatcherTests {

  LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
  ch.qos.logback.classic.Logger logger = context.getLogger(Logger.ROOT_LOGGER_NAME);

  private final Scope metricsScope = new NoopScope();

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void pollWorkflowTasksAreDispatchedBasedOnTaskQueueName() {
    AtomicBoolean handled = new AtomicBoolean(false);
    Functions.Proc1<PollWorkflowTaskQueueResponse> handler = r -> handled.set(true);

    PollWorkflowTaskDispatcher dispatcher =
        new PollWorkflowTaskDispatcher(
            testWorkflowRule.getWorkflowServiceStubs(), "default", metricsScope);
    dispatcher.subscribe("taskqueue1", handler);

    PollWorkflowTaskQueueResponse response = CreatePollWorkflowTaskQueueResponse("taskqueue1");
    dispatcher.process(response);

    assertTrue(handled.get());
  }

  @Test
  public void pollWorkflowTasksAreDispatchedToTheCorrectHandler() {
    AtomicBoolean handled = new AtomicBoolean(false);
    AtomicBoolean handled2 = new AtomicBoolean(false);

    Functions.Proc1<PollWorkflowTaskQueueResponse> handler = r -> handled.set(true);
    Functions.Proc1<PollWorkflowTaskQueueResponse> handler2 = r -> handled2.set(true);

    PollWorkflowTaskDispatcher dispatcher =
        new PollWorkflowTaskDispatcher(
            testWorkflowRule.getWorkflowServiceStubs(), "default", metricsScope);
    dispatcher.subscribe("taskqueue1", handler);
    dispatcher.subscribe("taskqueue2", handler2);

    PollWorkflowTaskQueueResponse response = CreatePollWorkflowTaskQueueResponse("taskqueue1");
    dispatcher.process(response);

    assertTrue(handled.get());
    assertFalse(handled2.get());
  }

  @Test
  public void handlersGetOverwrittenWhenRegisteredForTheSameTaskQueue() {
    AtomicBoolean handled = new AtomicBoolean(false);
    AtomicBoolean handled2 = new AtomicBoolean(false);

    Functions.Proc1<PollWorkflowTaskQueueResponse> handler = r -> handled.set(true);
    Functions.Proc1<PollWorkflowTaskQueueResponse> handler2 = r -> handled2.set(true);

    PollWorkflowTaskDispatcher dispatcher =
        new PollWorkflowTaskDispatcher(
            testWorkflowRule.getWorkflowServiceStubs(), "default", metricsScope);
    dispatcher.subscribe("taskqueue1", handler);
    dispatcher.subscribe("taskqueue1", handler2);

    PollWorkflowTaskQueueResponse response = CreatePollWorkflowTaskQueueResponse("taskqueue1");
    dispatcher.process(response);

    assertTrue(handled2.get());
    assertFalse(handled.get());
  }

  @Test
  @Ignore // TODO: Rewrite as mocking of WorkflowServiceBlockingStub is not possible
  public void aWarningIsLoggedAndWorkflowTaskIsFailedWhenNoHandlerIsRegisteredForTheTaskQueue() {
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
        new PollWorkflowTaskDispatcher(mockService, "default", metricsScope);
    dispatcher.subscribe("taskqueue1", handler);

    PollWorkflowTaskQueueResponse response =
        CreatePollWorkflowTaskQueueResponse("I Don't Exist TaskQueue");
    dispatcher.process(response);

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

  @Test
  public void testPollerOptionsRuntimeException() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);

    PollerOptions pollerOptions = PollerOptions.getDefaultInstance();
    Thread.UncaughtExceptionHandler exceptionHandler = pollerOptions.getUncaughtExceptionHandler();
    RuntimeException e =
        new RuntimeException(
            "UnhandledCommand",
            new StatusRuntimeException(
                Status.fromCode(Status.Code.INVALID_ARGUMENT).withDescription("UnhandledCommand")));
    exceptionHandler.uncaughtException(Thread.currentThread(), e);

    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.INFO, event.getLevel());
    assertEquals(PollerOptions.UNHANDLED_COMMAND_EXCEPTION_MESSAGE, event.getFormattedMessage());
  }

  private PollWorkflowTaskQueueResponse CreatePollWorkflowTaskQueueResponse(String taskQueueName) {
    return PollWorkflowTaskQueueResponse.newBuilder()
        .setWorkflowExecutionTaskQueue(TaskQueue.newBuilder().setName(taskQueueName).build())
        .build();
  }
}
