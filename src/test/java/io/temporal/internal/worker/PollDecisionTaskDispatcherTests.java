/*
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
import io.temporal.internal.testservice.TestWorkflowService;
import io.temporal.proto.common.TaskList;
import io.temporal.proto.workflowservice.PollForDecisionTaskResponse;
import io.temporal.proto.workflowservice.WorkflowServiceGrpc;
import io.temporal.serviceclient.GrpcWorkflowServiceFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PollDecisionTaskDispatcherTests {

  LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
  ch.qos.logback.classic.Logger logger = context.getLogger(Logger.ROOT_LOGGER_NAME);

  private TestWorkflowService testService;
  private GrpcWorkflowServiceFactory service;

  @Before
  public void setUp() {
    testService = new TestWorkflowService();
    service = testService.newClientStub();
  }

  @After
  public void tearDown() {
    service.close();
    testService.close();
  }

  @Test
  public void pollDecisionTasksAreDispatchedBasedOnTaskListName() {
    // Arrange
    AtomicBoolean handled = new AtomicBoolean(false);
    Consumer<PollForDecisionTaskResponse> handler = r -> handled.set(true);

    PollDecisionTaskDispatcher dispatcher = new PollDecisionTaskDispatcher(service);
    dispatcher.subscribe("tasklist1", handler);

    // Act
    PollForDecisionTaskResponse response = CreatePollForDecisionTaskResponse("tasklist1");
    dispatcher.process(response);

    // Assert
    assertTrue(handled.get());
  }

  @Test
  public void pollDecisionTasksAreDispatchedToTheCorrectHandler() {

    // Arrange
    AtomicBoolean handled = new AtomicBoolean(false);
    AtomicBoolean handled2 = new AtomicBoolean(false);

    Consumer<PollForDecisionTaskResponse> handler = r -> handled.set(true);
    Consumer<PollForDecisionTaskResponse> handler2 = r -> handled2.set(true);

    PollDecisionTaskDispatcher dispatcher = new PollDecisionTaskDispatcher(service);
    dispatcher.subscribe("tasklist1", handler);
    dispatcher.subscribe("tasklist2", handler2);

    // Act
    PollForDecisionTaskResponse response = CreatePollForDecisionTaskResponse("tasklist1");
    dispatcher.process(response);

    // Assert
    assertTrue(handled.get());
    assertFalse(handled2.get());
  }

  @Test
  public void handlersGetOverwrittenWhenRegisteredForTheSameTaskList() {

    // Arrange
    AtomicBoolean handled = new AtomicBoolean(false);
    AtomicBoolean handled2 = new AtomicBoolean(false);

    Consumer<PollForDecisionTaskResponse> handler = r -> handled.set(true);
    Consumer<PollForDecisionTaskResponse> handler2 = r -> handled2.set(true);

    PollDecisionTaskDispatcher dispatcher = new PollDecisionTaskDispatcher(service);
    dispatcher.subscribe("tasklist1", handler);
    dispatcher.subscribe("tasklist1", handler2);

    // Act
    PollForDecisionTaskResponse response = CreatePollForDecisionTaskResponse("tasklist1");
    dispatcher.process(response);

    // Assert
    assertTrue(handled2.get());
    assertFalse(handled.get());
  }

  @Test
  @Ignore // TODO: Rewrite as mocking of WorkflowServiceBlockingStub is not possible
  public void aWarningIsLoggedAndDecisionTaskIsFailedWhenNoHandlerIsRegisteredForTheTaskList()
      throws Exception {

    // Arrange
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);

    AtomicBoolean handled = new AtomicBoolean(false);
    Consumer<PollForDecisionTaskResponse> handler = r -> handled.set(true);

    WorkflowServiceGrpc.WorkflowServiceBlockingStub stub =
        mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
    GrpcWorkflowServiceFactory mockService = mock(GrpcWorkflowServiceFactory.class);
    when(mockService.blockingStub()).thenReturn(stub);

    PollDecisionTaskDispatcher dispatcher = new PollDecisionTaskDispatcher(mockService);
    dispatcher.subscribe("tasklist1", handler);

    // Act
    PollForDecisionTaskResponse response =
        CreatePollForDecisionTaskResponse("I Don't Exist TaskList");
    dispatcher.process(response);

    // Assert
    verify(stub, times(1)).respondDecisionTaskFailed(any());
    assertFalse(handled.get());
    assertEquals(1, appender.list.size());
    ILoggingEvent event = appender.list.get(0);
    assertEquals(Level.WARN, event.getLevel());
    assertEquals(
        String.format(
            "No handler is subscribed for the PollForDecisionTaskResponse.WorkflowExecutionTaskList %s",
            "I Don't Exist TaskList"),
        event.getFormattedMessage());
  }

  private PollForDecisionTaskResponse CreatePollForDecisionTaskResponse(String taskListName) {
    return PollForDecisionTaskResponse.newBuilder()
        .setWorkflowExecutionTaskList(TaskList.newBuilder().setName(taskListName).build())
        .build();
  }
}
