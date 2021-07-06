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

package io.temporal.testing;

import com.google.common.annotations.VisibleForTesting;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * TestWorkflowEnvironment provides workflow unit testing capabilities.
 *
 * <p>Testing the workflow code is hard as it might be potentially very long running. The included
 * in-memory implementation of the Temporal service supports <b>an automatic time skipping</b>.
 * Anytime a workflow under the test as well as the unit test code are waiting on a timer (or sleep)
 * the internal service time is automatically advanced to the nearest time that unblocks one of the
 * waiting threads. This way a workflow that runs in production for months is unit tested in
 * milliseconds. Here is an example of a test that executes in a few milliseconds instead of over
 * two hours that are needed for the workflow to complete:
 *
 * <pre><code>
 *   public class SignaledWorkflowImpl implements SignaledWorkflow {
 *     private String signalInput;
 *
 *    {@literal @}Override
 *     public String workflow1(String input) {
 *       Workflow.sleep(Duration.ofHours(1));
 *       Workflow.await(() -&gt; signalInput != null);
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
 *    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
 *    worker.registerWorkflowImplementationTypes(SignaledWorkflowImpl.class);
 *    worker.start();
 *
 *    // Creates a WorkflowClient that interacts with the server owned by the testEnvironment.
 *    WorkflowClient client = testEnvironment.getWorkflowClient();
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
@VisibleForTesting
public interface TestWorkflowEnvironment {

  static TestWorkflowEnvironment newInstance() {
    return new TestWorkflowEnvironmentInternal(TestEnvironmentOptions.getDefaultInstance());
  }

  static TestWorkflowEnvironment newInstance(TestEnvironmentOptions options) {
    return new TestWorkflowEnvironmentInternal(options);
  }

  /**
   * Creates a new Worker instance that is connected to the in-memory test Temporal service.
   *
   * @param taskQueue task queue to poll.
   */
  Worker newWorker(String taskQueue);

  /**
   * Creates a new Worker instance that is connected to the in-memory test Temporal service.
   *
   * @param taskQueue task queue to poll.
   */
  Worker newWorker(String taskQueue, WorkerOptions options);

  /** Creates a WorkflowClient that is connected to the in-memory test Temporal service. */
  WorkflowClient getWorkflowClient();

  /**
   * This time might not be equal to {@link System#currentTimeMillis()} due to time skipping.
   *
   * @return the current in-memory test Temporal service time in milliseconds.
   */
  long currentTimeMillis();

  /**
   * Wait until internal test Temporal service time passes the specified duration. This call also
   * indicates that workflow time might jump forward (if none of the activities are running) up to
   * the specified duration.
   */
  void sleep(Duration duration);

  /**
   * Registers a callback to run after the specified delay according to the test Temporal service
   * internal clock.
   */
  void registerDelayedCallback(Duration delay, Runnable r);

  /** @return the in-memory test Temporal service that is owned by this. */
  WorkflowServiceStubs getWorkflowService();

  String getNamespace();

  /**
   * Currently prints histories of all workflow instances stored in the service. This is useful
   * information to print in the case of a unit test failure. A convenient way to achieve this is to
   * add the following Rule to a unit test:
   *
   * <pre><code>
   *  {@literal @}Rule
   *   public TestWatcher watchman =
   *       new TestWatcher() {
   *        {@literal @}Override
   *         protected void failed(Throwable e, Description description) {
   *           System.err.println(testEnvironment.getDiagnostics());
   *           testEnvironment.close();
   *         }
   *       };
   * </code></pre>
   *
   * @return the diagnostic data about the internal service state.
   */
  String getDiagnostics();

  /** Calls {@link #shutdownNow()} and {@link #awaitTermination(long, TimeUnit)}. */
  void close();

  WorkerFactory getWorkerFactory();

  /** Start all workers created by this factory. */
  void start();

  /** Was {@link #start()} called? */
  boolean isStarted();

  /** Was {@link #shutdownNow()} or {@link #shutdown()} called? */
  boolean isShutdown();

  /** Are all tasks done after {@link #shutdownNow()} or {@link #shutdown()}? */
  boolean isTerminated();

  /**
   * Initiates an orderly shutdown in which polls are stopped and already received workflow and
   * activity tasks are executed. After the shutdown calls to {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)} start throwing {@link
   * io.temporal.client.ActivityWorkerShutdownException}. Invocation has no additional effect if
   * already shut down. This method does not wait for previously received tasks to complete
   * execution. Use {@link #awaitTermination(long, TimeUnit)} to do that.
   */
  void shutdown();

  /**
   * Initiates an orderly shutdown in which polls are stopped and already received workflow and
   * activity tasks are attempted to be stopped. This implementation cancels tasks via
   * Thread.interrupt(), so any task that fails to respond to interrupts may never terminate. Also
   * after the shutdownNow calls to {@link
   * io.temporal.activity.ActivityExecutionContext#heartbeat(Object)} start throwing {@link
   * io.temporal.client.ActivityWorkerShutdownException}. Invocation has no additional effect if
   * already shut down. This method does not wait for previously received tasks to complete
   * execution. Use {@link #awaitTermination(long, TimeUnit)} to do that.
   */
  void shutdownNow();

  /**
   * Blocks until all tasks have completed execution after a shutdown request, or the timeout
   * occurs, or the current thread is interrupted, whichever happens first.
   */
  void awaitTermination(long timeout, TimeUnit unit);
}
