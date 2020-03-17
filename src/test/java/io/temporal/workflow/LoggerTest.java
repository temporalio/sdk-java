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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.logging.LoggerTag;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.UUID;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggerTest {

  private static final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  static {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
    listAppender.setContext(context);
    listAppender.start();
    logger.addAppender(listAppender);
  }

  private static final String taskList = "logger-test";

  public interface TestWorkflow {
    @WorkflowMethod
    void execute(String id);
  }

  public static class TestLoggingInWorkflow implements LoggerTest.TestWorkflow {
    private final Logger workflowLogger = Workflow.getLogger(TestLoggingInWorkflow.class);

    @Override
    public void execute(String id) {
      workflowLogger.info("Start executing workflow {}.", id);
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setTaskList(taskList).build();
      LoggerTest.TestChildWorkflow workflow =
          Workflow.newChildWorkflowStub(LoggerTest.TestChildWorkflow.class, options);
      workflow.executeChild(id);
      workflowLogger.info("Done executing workflow {}.", id);
    }
  }

  public interface TestChildWorkflow {
    @WorkflowMethod
    void executeChild(String id);
  }

  public static class TestLoggerInChildWorkflow implements LoggerTest.TestChildWorkflow {
    private static final Logger childWorkflowLogger =
        Workflow.getLogger(TestLoggerInChildWorkflow.class);

    @Override
    public void executeChild(String id) {
      childWorkflowLogger.info("Executing child workflow {}.", id);
    }
  }

  @Test
  public void testWorkflowLogger() {
    TestEnvironmentOptions testOptions =
        TestEnvironmentOptions.newBuilder()
            .setDomain(WorkflowTest.DOMAIN)
            .setEnableLoggingInReplay(false)
            .build();
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(testOptions);
    Worker worker = env.newWorker(taskList);
    worker.registerWorkflowImplementationTypes(
        TestLoggingInWorkflow.class, TestLoggerInChildWorkflow.class);
    env.start();

    WorkflowClient workflowClient = env.getWorkflowClient();
    WorkflowOptions options =
        new WorkflowOptions.Builder()
            .setExecutionStartToCloseTimeout(Duration.ofSeconds(1000))
            .setTaskList(taskList)
            .build();
    LoggerTest.TestWorkflow workflow =
        workflowClient.newWorkflowStub(LoggerTest.TestWorkflow.class, options);
    String wfID = UUID.randomUUID().toString();
    workflow.execute(wfID);

    assertEquals(1, matchingLines(String.format("Start executing workflow %s.", wfID)));
    assertEquals(1, matchingLines(String.format("Executing child workflow %s.", wfID)));
    assertEquals(1, matchingLines(String.format("Done executing workflow %s.", wfID)));
  }

  private int matchingLines(String message) {
    int i = 0;
    for (ILoggingEvent event : listAppender.list) {
      if (event.getFormattedMessage().contains(message)) {
        assertTrue(event.getMDCPropertyMap().containsKey(LoggerTag.WORKFLOW_ID));
        assertTrue(event.getMDCPropertyMap().containsKey(LoggerTag.WORKFLOW_TYPE));
        assertTrue(event.getMDCPropertyMap().containsKey(LoggerTag.RUN_ID));
        assertTrue(event.getMDCPropertyMap().containsKey(LoggerTag.TASK_LIST));
        i++;
      }
    }
    return i;
  }
}
