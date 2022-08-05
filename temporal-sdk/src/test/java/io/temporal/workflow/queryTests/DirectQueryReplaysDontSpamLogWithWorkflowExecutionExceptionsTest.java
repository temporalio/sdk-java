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
import io.temporal.client.WorkflowException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.Issue;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
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
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowNonRetryableFlag.class).build();

  @Before
  public void setUp() throws Exception {
    workflowCodeExecutionCount.set(0);

    Logger workflowExecuteRunnableLogger =
        (Logger) LoggerFactory.getLogger("io.temporal.internal.sync.WorkflowExecuteRunnable");
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
