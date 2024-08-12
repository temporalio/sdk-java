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

package io.temporal.internal.replay;

import static io.temporal.internal.replay.ReplayWorkflowExecutor.unfinishedSignalHandlesWarnMessage;
import static io.temporal.internal.replay.ReplayWorkflowExecutor.unfinishedUpdateHandlesWarnMessage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.temporal.client.*;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.util.List;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.slf4j.LoggerFactory;

public class WarnUnfinishedHandlers {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestSignalWorkflowImpl.class).build();

  // Get Logback Logger
  Logger replayWorkflowExecutorLogger =
      (Logger) LoggerFactory.getLogger(ReplayWorkflowExecutor.class);
  ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  @Before
  public void setUp() {
    listAppender.start();
    replayWorkflowExecutorLogger.addAppender(listAppender);
  }

  private WorkflowWithDanglingHandlers setupWorkflow() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithDanglingHandlers workflow =
        workflowClient.newWorkflowStub(WorkflowWithDanglingHandlers.class, options);
    WorkflowClient.start(workflow::execute);
    testWorkflowRule.waitForTheEndOfWFT(workflowId);
    // Send a bunch of signals to warn
    for (int i = 0; i < 5; i++) {
      workflow.warningSignalHandler();
      // Wait for the signal to be processed to ensure the signal event ID is deterministic
      testWorkflowRule.waitForTheEndOfWFT(workflowId);
    }
    for (int i = 0; i < 5; i++) {
      workflow.nonWarningSignalHandler();
      testWorkflowRule.waitForTheEndOfWFT(workflowId);
    }
    // Send a bunch of updates to warn
    WorkflowStub stub = WorkflowStub.fromTyped(workflow);
    for (int i = 0; i < 5; i++) {
      // Set an update ID to ensure the update event ID is deterministic
      stub.startUpdate(
          UpdateOptions.newBuilder(Integer.class)
              .setUpdateName("warningUpdateHandler")
              .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
              .setUpdateId(String.valueOf(i))
              .build());
    }
    for (int i = 0; i < 5; i++) {
      stub.startUpdate("nonWarningUpdateHandler", WorkflowUpdateStage.ACCEPTED, Integer.class);
    }
    return workflow;
  }

  void assertLogs(Boolean expectLogs) {
    List<ILoggingEvent> logsList = listAppender.list;
    if (expectLogs) {
      assertEquals(Level.WARN, logsList.get(0).getLevel());
      assertEquals(unfinishedSignalHandlesWarnMessage, logsList.get(0).getMessage());
      assertEquals(
          logsList.get(0).getMDCPropertyMap().get("Signals"),
          "[SignalHandlerInfo{eventId=5, name='warningSignalHandler', policy=WARN_AND_ABANDON}, SignalHandlerInfo{eventId=9, name='warningSignalHandler', policy=WARN_AND_ABANDON}, SignalHandlerInfo{eventId=13, name='warningSignalHandler', policy=WARN_AND_ABANDON}, SignalHandlerInfo{eventId=17, name='warningSignalHandler', policy=WARN_AND_ABANDON}, SignalHandlerInfo{eventId=21, name='warningSignalHandler', policy=WARN_AND_ABANDON}]");

      assertEquals(Level.WARN, logsList.get(1).getLevel());
      assertEquals(unfinishedUpdateHandlesWarnMessage, logsList.get(1).getMessage());
      assertEquals(
          logsList.get(1).getMDCPropertyMap().get("Updates"),
          "[UpdateHandlerInfo{updateId='0', name='warningUpdateHandler', policy=WARN_AND_ABANDON}, UpdateHandlerInfo{updateId='1', name='warningUpdateHandler', policy=WARN_AND_ABANDON}, UpdateHandlerInfo{updateId='2', name='warningUpdateHandler', policy=WARN_AND_ABANDON}, UpdateHandlerInfo{updateId='3', name='warningUpdateHandler', policy=WARN_AND_ABANDON}, UpdateHandlerInfo{updateId='4', name='warningUpdateHandler', policy=WARN_AND_ABANDON}]");
    } else {
      assertEquals(0, logsList.size());
    }
  }

  @Test
  public void warnOnWorkflowComplete() {
    WorkflowWithDanglingHandlers workflow = setupWorkflow();
    // Try to complete the workflow
    workflow.complete(CompletionMethod.COMPLETE);
    assertEquals(0, workflow.execute());
    assertLogs(true);
  }

  @Test
  public void doesNotWarnOnWorkflowFail() {
    WorkflowWithDanglingHandlers workflow = setupWorkflow();
    // Try to complete the workflow
    workflow.complete(CompletionMethod.FAIL);
    assertThrows(WorkflowFailedException.class, () -> workflow.execute());
    assertLogs(false);
  }

  @Test
  public void warnOnWorkflowCancelled() {
    WorkflowWithDanglingHandlers workflow = setupWorkflow();
    // Cancel the workflow
    WorkflowStub.fromTyped(workflow).cancel();
    assertThrows(WorkflowFailedException.class, () -> workflow.execute());
    assertLogs(true);
  }

  @Test
  public void warnOnWorkflowContinueAsNew() {
    WorkflowWithDanglingHandlers workflow = setupWorkflow();
    // Request the workflow CAN
    workflow.complete(CompletionMethod.CONTINUE_AS_NEW);
    assertEquals(0, workflow.execute());
    assertLogs(true);
  }

  @WorkflowInterface
  public interface WorkflowWithDanglingHandlers {

    @WorkflowMethod
    int execute();

    @SignalMethod
    void warningSignalHandler();

    @SignalMethod(unfinishedPolicy = HandlerUnfinishedPolicy.ABANDON)
    void nonWarningSignalHandler();

    @UpdateMethod
    void warningUpdateHandler();

    @UpdateMethod(unfinishedPolicy = HandlerUnfinishedPolicy.ABANDON)
    void nonWarningUpdateHandler();

    @SignalMethod
    void complete(CompletionMethod method);
  }

  public static class TestSignalWorkflowImpl implements WorkflowWithDanglingHandlers {
    int handlersFinished = 0;
    boolean blocked = false;
    CompletablePromise<CompletionMethod> promise = Workflow.newPromise();

    @Override
    public int execute() {
      if (Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
        return 0;
      }
      CompletionMethod method = promise.cancellableGet();
      if (method == CompletionMethod.COMPLETE) {
        return handlersFinished;
      } else if (method == CompletionMethod.FAIL) {
        blocked = true;
        throw ApplicationFailure.newFailure("test failure", "TestFailure");
      } else if (method == CompletionMethod.CONTINUE_AS_NEW) {
        Workflow.continueAsNew();
      }
      return handlersFinished;
    }

    @Override
    public void complete(CompletionMethod method) {
      promise.complete(method);
    }

    @Override
    public void warningSignalHandler() {
      Workflow.await(() -> blocked);
      handlersFinished++;
    }

    @Override
    public void nonWarningSignalHandler() {
      Workflow.await(() -> false);
      handlersFinished++;
    }

    @Override
    public void warningUpdateHandler() {
      Workflow.await(() -> blocked);
      handlersFinished++;
    }

    @Override
    public void nonWarningUpdateHandler() {
      Workflow.await(() -> false);
      handlersFinished++;
    }
  }

  enum CompletionMethod {
    COMPLETE,
    FAIL,
    CONTINUE_AS_NEW,
  }
}
