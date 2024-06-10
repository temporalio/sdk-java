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

package io.temporal.workflow.queryTests;

import static org.junit.Assert.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.Issue;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * Verifies that full replays of failed workflows for queries don't spam log with misleading
 * workflow exceptions that look like original workflow execution exceptions.
 */
@Issue("https://github.com/temporalio/sdk-java/issues/1348")
public class DirectQueryReplaysDontSpamLogWithWorkflowExecutionExceptionsTest {

  private static final AtomicInteger workflowCodeExecutionCount = new AtomicInteger();
  private final ListAppender<ILoggingEvent> workflowExecuteRunnableLoggerAppender =
      new ListAppender<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestWorkflowNonRetryableFlag.class, LogAndKeepRunningWorkflow.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .build();

  @Before
  public void setUp() throws Exception {
    workflowCodeExecutionCount.set(0);

    Logger workflowExecuteRunnableLogger =
        (Logger) LoggerFactory.getLogger("io.temporal.internal.sync.WorkflowExecutionHandler");
    workflowExecuteRunnableLoggerAppender.start();
    workflowExecuteRunnableLogger.addAppender(workflowExecuteRunnableLoggerAppender);
  }

  @Test
  public void queriedWorkflowFailureDoesntProduceAdditionalLogs() {
    TestWorkflows.TestWorkflowWithQuery testWorkflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class);
    assertThrows(WorkflowException.class, testWorkflow::execute);
    assertEquals(
        "Workflow execution exception should be logged",
        1,
        workflowExecuteRunnableLoggerAppender.list.size());
    testWorkflow.query();
    assertEquals(2, workflowCodeExecutionCount.get());
    assertEquals(
        "There was two execution - one original and one full replay for query. "
            + "But only one original exception should be logged.",
        1,
        workflowExecuteRunnableLoggerAppender.list.size());
  }

  @Test
  public void queriedWorkflowFailureDoesntProduceAdditionalLogsWhenWorkflowIsNotCompleted() {
    TestWorkflows.QueryableWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.QueryableWorkflow.class);

    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    assertEquals("my-state", workflow.getState());
    assertEquals("There was only one execution.", 1, workflowCodeExecutionCount.get());

    testWorkflowRule.invalidateWorkflowCache();
    assertEquals("my-state", workflow.getState());
    assertEquals(
        "There was two executions - one original and one full replay for query.",
        2,
        workflowCodeExecutionCount.get());

    workflow.mySignal("exit");
    assertEquals("exit", workflow.execute());
    assertEquals("my-state", workflow.getState());
    assertEquals(
        "There was three executions - one original and two full replays for query.",
        3,
        workflowCodeExecutionCount.get());
    assertEquals(
        "Only the original exception should be logged.",
        1,
        workflowExecuteRunnableLoggerAppender.list.size());
  }

  public static class LogAndKeepRunningWorkflow implements TestWorkflows.QueryableWorkflow {
    private final org.slf4j.Logger logger =
        Workflow.getLogger("io.temporal.internal.sync.WorkflowExecutionHandler");
    private final TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build());
    private boolean exit;

    @Override
    public String execute() {
      workflowCodeExecutionCount.incrementAndGet();
      while (true) {
        try {
          activities.throwIO();
        } catch (ActivityFailure e) {
          logger.error("Unexpected error on activity", e);
          Workflow.await(() -> exit);
          return "exit";
        }
      }
    }

    @Override
    public String getState() {
      return "my-state";
    }

    @Override
    public void mySignal(String value) {
      exit = true;
    }
  }

  public static class TestWorkflowNonRetryableFlag implements TestWorkflows.TestWorkflowWithQuery {

    @Override
    public String execute() {
      workflowCodeExecutionCount.incrementAndGet();
      throw ApplicationFailure.newNonRetryableFailure("SomeMessage", "SomeType");
    }

    @Override
    public String query() {
      return null;
    }
  }
}
