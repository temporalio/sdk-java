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

package com.uber.cadence.testing;

import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.internal.sync.TestWorkflowEnvironmentInternal;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.worker.Worker;
import java.time.Duration;

/**
 * TestWorkflowEnvironment provides workflow unit testing capabilities.
 *
 * <p>Testing workflow code is hard as it might be potentially very long running. The included
 * in-memory implementation of the Cadence service supports <b>automatic time skipping</b>. Anytime
 * a workflow under the test as well as a unit test code are waiting on a timer (or sleep) the
 * internal service time is automatically advanced to the nearest time that unblocks one of the
 * waiting threads. This way a workflow that runs in production for months is unit tested in
 * milliseconds. Here is an example of a test that executes in a few milliseconds instead of over
 * two hours which is needed for the workflow to complete:
 *
 * <pre><code>
 *   public class SignaledWorkflowImpl implements SignaledWorkflow {
 *     private String signalInput;
 *
 *    {@literal @}Override
 *     public String workflow1(String input) {
 *       Workflow.sleep(Duration.ofHours(1));
 *       Workflow.await(() -> signalInput != null);
 *       Workflow.sleep(Duration.ofHours(1));
 *       return signalInput + "-" + input;
 *     }
 *
 *    {@literal @}Override
 *     public void processSignal(String input) {
 *       signalInput = input;
 *    }
 *  }
 *
 * {@literal @}Test
 *  public void testSignal() throws ExecutionException, InterruptedException {
 *    TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance();
 *
 *    // Creates a worker that polls tasks from the service owned by the testEnvironment.
 *    Worker worker = testEnvironment.newWorker(TASK_LIST);
 *    worker.registerWorkflowImplementationTypes(SignaledWorkflowImpl.class);
 *    worker.start();
 *
 *    // Creates a WorkflowClient that interacts with the server owned by the testEnvironment.
 *    WorkflowClient client = testEnvironment.newWorkflowClient();
 *    SignaledWorkflow workflow = client.newWorkflowStub(SignaledWorkflow.class);
 *
 *    // Starts a workflow execution
 *    CompletableFuture<String> result = WorkflowClient.execute(workflow::workflow1, "input1");
 *
 *    // The sleep forwards the service clock for 65 minutes without blocking.
 *    // This ensures that the signal is sent after the one hour sleep in the workflow code.
 *    testEnvironment.sleep(Duration.ofMinutes(65));
 *    workflow.processSignal("signalInput");
 *
 *    // Blocks until workflow is complete. Workflow sleep forwards clock for one hour and
 *    // this call returns almost immediately.
 *    assertEquals("signalInput-input1", result.get());
 *
 *    // Closes workers and releases in-memory service.
 *    testEnvironment.close();
 *  }
 *
 * </code></pre>
 */
public interface TestWorkflowEnvironment {

  static TestWorkflowEnvironment newInstance() {
    return new TestWorkflowEnvironmentInternal(new TestEnvironmentOptions.Builder().build());
  }

  static TestWorkflowEnvironment newInstance(TestEnvironmentOptions options) {
    return new TestWorkflowEnvironmentInternal(options);
  }

  /**
   * Creates a new Worker instance that is connected to the in-memory test Cadence service. {@link
   * #close()} calls {@link Worker#shutdown(Duration)} for all workers created through this method.
   *
   * @param taskList task list to poll.
   */
  Worker newWorker(String taskList);

  /** Creates a WorkflowClient that is connected to the in-memory test Cadence service. */
  WorkflowClient newWorkflowClient();

  /**
   * Creates a WorkflowClient that is connected to the in-memory test Cadence service.
   *
   * @param clientOptions options used to configure the client.
   */
  WorkflowClient newWorkflowClient(WorkflowClientOptions clientOptions);

  /**
   * Returns the current in-memory test Cadence service time in milliseconds. This time might not be
   * equal to {@link System#currentTimeMillis()} due to time skipping.
   */
  long currentTimeMillis();

  /**
   * Wait until internal test Cadence service time passes the specified duration. This call also
   * indicates that workflow time might jump forward (if none of the activities are running) up to
   * the specified duration.
   */
  void sleep(Duration duration);

  /**
   * Registers a callback to run after the specified delay according to the test Cadence service
   * internal clock.
   */
  void registerDelayedCallback(Duration delay, Runnable r);

  /** Returns the in-memory test Cadence service that is owned by this. */
  IWorkflowService getWorkflowService();

  String getDomain();

  /**
   * Returns diagnostic data about the internal service state to the provided {@link StringBuilder}.
   * Currently prints histories of all workflow instances stored in the service. This is useful to
   * print in case of a unit test failure. One way to achieve it is to add the following Rule to a
   * unit test:
   *
   * <pre><code>
   *  {@literal @}Rule
   *   public TestWatcher watchman =
   *       new TestWatcher() {
   *        {@literal @}Override
   *         protected void failed(Throwable e, Description description) {
   *           System.err.println(testEnvironment.getDiagnostics());
   *         }
   *       };
   * </code></pre>
   */
  String getDiagnostics();

  /**
   * Calls {@link Worker#shutdown(Duration)} on all workers created through {@link
   * #newWorker(String)} and closes the in-memory Cadence service.
   */
  void close();
}
