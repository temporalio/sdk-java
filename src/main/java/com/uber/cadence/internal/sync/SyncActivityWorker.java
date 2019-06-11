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

package com.uber.cadence.internal.sync;

import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.worker.ActivityWorker;
import com.uber.cadence.internal.worker.SingleWorkerOptions;
import com.uber.cadence.internal.worker.SuspendableWorker;
import com.uber.cadence.serviceclient.IWorkflowService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Activity worker that supports POJO activity implementations. */
public class SyncActivityWorker implements SuspendableWorker {

  private final ActivityWorker worker;
  private final POJOActivityTaskHandler taskHandler;
  private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(4);

  public SyncActivityWorker(
      IWorkflowService service, String domain, String taskList, SingleWorkerOptions options) {
    taskHandler =
        new POJOActivityTaskHandler(service, domain, options.getDataConverter(), heartbeatExecutor);
    worker = new ActivityWorker(service, domain, taskList, options, taskHandler);
  }

  public void setActivitiesImplementation(Object... activitiesImplementation) {
    taskHandler.setActivitiesImplementation(activitiesImplementation);
  }

  @Override
  public void start() {
    worker.start();
  }

  @Override
  public boolean isStarted() {
    return worker.isStarted();
  }

  @Override
  public boolean isShutdown() {
    return worker.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return worker.isTerminated() && heartbeatExecutor.isTerminated();
  }

  @Override
  public void shutdown() {
    worker.shutdown();
    heartbeatExecutor.shutdown();
  }

  @Override
  public void shutdownNow() {
    worker.shutdownNow();
    heartbeatExecutor.shutdownNow();
  }

  @Override
  public void awaitTermination(long timeout, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(timeout);
    timeoutMillis = InternalUtils.awaitTermination(worker, timeoutMillis);
    InternalUtils.awaitTermination(heartbeatExecutor, timeoutMillis);
  }

  @Override
  public void suspendPolling() {
    worker.suspendPolling();
  }

  @Override
  public void resumePolling() {
    worker.resumePolling();
  }

  @Override
  public boolean isSuspended() {
    return worker.isSuspended();
  }
}
