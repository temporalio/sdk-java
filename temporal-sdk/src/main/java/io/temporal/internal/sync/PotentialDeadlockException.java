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

package io.temporal.internal.sync;

/**
 * A workflow tasks are allowed to execute only some limited amount of time without interruption. If
 * workflow task runs longer than specified interval without yielding (like calling an Activity), it
 * will fail automatically with this exceptions. This is done to detect deadlocks in workflows and
 * based on the assumptions that workflow code shouldn't be blocking.
 */
public class PotentialDeadlockException extends RuntimeException {

  private final WorkflowThreadContext workflowThreadContext;
  private final long detectionTimestamp;
  private final long stacktraceTimestamp;
  private String threadDump;
  private long threadDumpTimestamp;

  /**
   * Note: because of JVM checkpoints, a timestamp when SDK detects a deadlock and a timestamp when
   * it's able to take a thread's stacktrace may differ. It may produce misleading stacktraces when
   * the thread stacktrace is taken from a different point then where it was originally when the
   * deadlock detection fired. This can give a false lead to the investigator. For that reason, the
   * exception message includes numerous timestamps to provide more context and make this tricky
   * scenario more obvious.
   *
   * @param threadName name of the thread that is in a potential deadlock state
   * @param stackTrace stacktrace of the thread that is in a potential deadlock state
   * @param workflowThreadContext context of the thread that is in a potential deadlock state
   * @param detectionTimestamp a timestamp the deadlock was detected
   * @param stacktraceTimestamp a timestamp when the stacktrace of the potentially deadlocked thread
   *     was taken.
   */
  PotentialDeadlockException(
      String threadName,
      StackTraceElement[] stackTrace,
      WorkflowThreadContext workflowThreadContext,
      long detectionTimestamp,
      long stacktraceTimestamp) {
    super(
        "Potential deadlock detected. workflow thread \""
            + threadName
            + "\" didn't yield control for over a second.",
        null,
        true,
        true);
    setStackTrace(stackTrace);
    this.workflowThreadContext = workflowThreadContext;
    this.detectionTimestamp = detectionTimestamp;
    this.stacktraceTimestamp = stacktraceTimestamp;
  }

  /**
   * @param stackDump stack dump of other threads of the workflow excluding the thread that
   *     triggered the deadlock detector
   * @param threadDumpTimestamp timestamp when the thread dump was taken
   */
  void setStackDump(String stackDump, long threadDumpTimestamp) {
    this.threadDump = stackDump;
    this.threadDumpTimestamp = threadDumpTimestamp;
  }

  @Override
  public String getMessage() {
    return super.getMessage()
        + " {"
        + ("detectionTimestamp=" + detectionTimestamp)
        + ("stacktraceTimestamp=" + stacktraceTimestamp)
        + ("threadDumpTimestamp=" + threadDumpTimestamp)
        + "}"
        + (!threadDump.isEmpty() ? " Other workflow threads:\n" + "[\n" + threadDump + "]\n" : "");
  }

  public WorkflowThreadContext getWorkflowThreadContext() {
    return this.workflowThreadContext;
  }
}
