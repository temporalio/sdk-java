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

package com.uber.cadence.activity;

import com.uber.cadence.internal.sync.ActivityInternal;
import com.uber.cadence.internal.sync.WorkflowInternal;
import com.uber.cadence.serviceclient.IWorkflowService;
import com.uber.cadence.workflow.ActivityException;
import com.uber.cadence.workflow.ActivityTimeoutException;
import java.util.concurrent.CancellationException;

/**
 * Use this method from within activity implementation to get information about activity task and
 * heartbeat.
 */
public final class Activity {

  /**
   * If this method is called during an activity execution then activity is not going to complete
   * when its method returns. It is expected to be completed asynchronously using {@link
   * com.uber.cadence.client.ActivityCompletionClient}.
   */
  public static void doNotCompleteOnReturn() {
    ActivityInternal.doNotCompleteOnReturn();
  }

  /**
   * @return task token that is required to report task completion when manual activity completion
   *     is used.
   */
  public static byte[] getTaskToken() {
    return ActivityInternal.getTask().getTaskToken();
  }

  /** @return workfow execution that requested the activity execution */
  public static com.uber.cadence.WorkflowExecution getWorkflowExecution() {
    return ActivityInternal.getTask().getWorkflowExecution();
  }

  /** @return task that caused activity execution */
  public static ActivityTask getTask() {
    return ActivityInternal.getTask();
  }

  /**
   * Use to notify Cadence service that activity execution is alive.
   *
   * @param details In case of activity timeout can be accessed through {@link
   *     ActivityTimeoutException#getDetails(Class)} method.
   * @throws CancellationException Indicates that activity cancellation was requested by the
   *     workflow.Should be rethrown from activity implementation to indicate successful
   *     cancellation.
   */
  public static void heartbeat(Object details) throws CancellationException {
    ActivityInternal.recordActivityHeartbeat(details);
  }

  /**
   * @return an instance of the Simple Workflow Java client that is the same used by the invoked
   *     activity worker. It can be useful if activity wants to use WorkflowClient for some
   *     operations like sending signal to its parent workflow. @TODO getWorkflowClient method to
   *     hide the service.
   */
  public static IWorkflowService getService() {
    return ActivityInternal.getService();
  }

  public static String getDomain() {
    return ActivityInternal.getDomain();
  }

  /**
   * If there is a need to return a checked exception from an activity do not add the exception to a
   * method signature but rethrow it using this method. The library code will unwrap it
   * automatically when propagating exception to the caller. There is no need to wrap unchecked
   * exceptions, but it is safe to call this method on them.
   *
   * <p>The reason for such design is that returning originally thrown exception from a remote call
   * (which child workflow and activity invocations are ) would not allow adding context information
   * about a failure, like activity and child workflow id. So stubs always throw a subclass of
   * {@link ActivityException} from calls to an activity and subclass of {@link
   * com.uber.cadence.workflow.ChildWorkflowException} from calls to a child workflow. The original
   * exception is attached as a cause to these wrapper exceptions. So as exceptions are always
   * wrapped adding checked ones to method signature causes more pain than benefit.
   *
   * <p>Throws original exception if e is {@link RuntimeException} or {@link Error}. Never returns.
   * But return type is not empty to be able to use it as:
   *
   * <pre>
   * try {
   *     return someCall();
   * } catch (Exception e) {
   *     throw CheckedExceptionWrapper.throwWrapped(e);
   * }
   * </pre>
   *
   * If throwWrapped returned void it wouldn't be possible to write <code>
   * throw CheckedExceptionWrapper.throwWrapped</code> and compiler would complain about missing
   * return.
   *
   * @return never returns as always throws.
   */
  public static RuntimeException wrap(Exception e) {
    return WorkflowInternal.wrap(e);
  }

  /** Prohibit instantiation */
  private Activity() {}
}
