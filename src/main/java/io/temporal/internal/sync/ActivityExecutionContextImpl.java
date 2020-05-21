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

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityTask;
import io.temporal.client.ActivityCancelledException;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.ActivityCompletionFailureException;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.proto.common.Payloads;
import io.temporal.proto.common.WorkflowExecution;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatRequest;
import io.temporal.proto.workflowservice.RecordActivityTaskHeartbeatResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.lang.reflect.Type;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base implementation of an {@link ActivityExecutionContext}.
 *
 * @author fateev, suskin
 * @see ActivityExecutionContext
 */
class ActivityExecutionContextImpl implements ActivityExecutionContext {

  private static final Logger log = LoggerFactory.getLogger(ActivityExecutionContextImpl.class);
  private static final long HEARTBEAT_RETRY_WAIT_MILLIS = 1000;
  private static final long MAX_HEARTBEAT_INTERVAL_MILLIS = 30000;

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final ActivityTask task;
  private final DataConverter dataConverter;
  private boolean doNotCompleteOnReturn;
  private final long heartbeatIntervalMillis;
  private Optional<Object> lastDetails;
  private boolean hasOutstandingHeartbeat;
  private final ScheduledExecutorService heartbeatExecutor;
  private Lock lock = new ReentrantLock();
  private ScheduledFuture future;
  private ActivityCompletionException lastException;

  /** Create an ActivityExecutionContextImpl with the given attributes. */
  ActivityExecutionContextImpl(
      WorkflowServiceStubs service,
      String namespace,
      ActivityTask task,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor) {
    this.namespace = namespace;
    this.service = service;
    this.task = task;
    this.dataConverter = dataConverter;
    this.heartbeatIntervalMillis =
        Math.min(
            (long) (0.8 * task.getHeartbeatTimeout().toMillis()), MAX_HEARTBEAT_INTERVAL_MILLIS);
    this.heartbeatExecutor = heartbeatExecutor;
  }

  /** @see ActivityExecutionContext#recordActivityHeartbeat(Object) */
  @Override
  public <V> void recordActivityHeartbeat(V details) throws ActivityCompletionException {
    if (heartbeatExecutor.isShutdown()) {
      throw new ActivityWorkerShutdownException(task);
    }
    lock.lock();
    try {
      // always set lastDetail. Successful heartbeat will clear it.
      lastDetails = Optional.ofNullable(details);
      hasOutstandingHeartbeat = true;
      // Only do sync heartbeat if there is no such call scheduled.
      if (future == null) {
        doHeartBeat(details);
      }
      if (lastException != null) {
        throw lastException;
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsType) {
    lock.lock();
    try {
      if (lastDetails != null) {
        @SuppressWarnings("unchecked")
        Optional<V> result = (Optional<V>) this.lastDetails;
        return result;
      }
      Optional<Payloads> details = task.getHeartbeatDetails();
      return Optional.ofNullable(dataConverter.fromData(details, detailsClass, detailsType));
    } finally {
      lock.unlock();
    }
  }

  private void doHeartBeat(Object details) {
    long nextHeartbeatDelay;
    try {
      sendHeartbeatRequest(details);
      hasOutstandingHeartbeat = false;
      nextHeartbeatDelay = heartbeatIntervalMillis;
    } catch (StatusRuntimeException e) {
      // Not rethrowing to not fail activity implementation on intermittent connection or Temporal
      // errors.
      log.warn("Heartbeat failed", e);
      nextHeartbeatDelay = HEARTBEAT_RETRY_WAIT_MILLIS;
    } catch (Exception e) {
      log.error("Unexpected exception", e);
      nextHeartbeatDelay = HEARTBEAT_RETRY_WAIT_MILLIS;
    }

    scheduleNextHeartbeat(nextHeartbeatDelay);
  }

  private void scheduleNextHeartbeat(long delay) {
    ScheduledFuture<?> f =
        heartbeatExecutor.schedule(
            () -> {
              lock.lock();
              try {
                if (hasOutstandingHeartbeat) {
                  Object details = lastDetails.orElse(null);
                  doHeartBeat(details);
                } else {
                  future = null;
                }
              } finally {
                lock.unlock();
              }
            },
            delay,
            TimeUnit.MILLISECONDS);
    lock.lock();
    try {
      future = f;
    } finally {
      lock.unlock();
    }
  }

  private void sendHeartbeatRequest(Object details) {
    RecordActivityTaskHeartbeatRequest.Builder r =
        RecordActivityTaskHeartbeatRequest.newBuilder()
            .setTaskToken(OptionsUtils.toByteString(task.getTaskToken()));
    Optional<Payloads> payloads = dataConverter.toData(details);
    if (payloads.isPresent()) {
      r.setDetails(payloads.get());
    }
    RecordActivityTaskHeartbeatResponse status;
    try {
      status = service.blockingStub().recordActivityTaskHeartbeat(r.build());
      if (status.getCancelRequested()) {
        lastException = new ActivityCancelledException(task);
      } else {
        lastException = null;
      }
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        lastException = new ActivityNotExistsException(task, e);
      } else if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT
          || e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
        lastException = new ActivityCompletionFailureException(task, e);
      } else {
        throw e;
      }
    }
  }

  @Override
  public void doNotCompleteOnReturn() {
    doNotCompleteOnReturn = true;
  }

  @Override
  public boolean isDoNotCompleteOnReturn() {
    return doNotCompleteOnReturn;
  }

  /** @see ActivityExecutionContext#getTask() */
  @Override
  public ActivityTask getTask() {
    return task;
  }

  /** @see ActivityExecutionContext#getService() */
  @Override
  public WorkflowServiceStubs getService() {
    return service;
  }

  @Override
  public byte[] getTaskToken() {
    return task.getTaskToken();
  }

  @Override
  public WorkflowExecution getWorkflowExecution() {
    return task.getWorkflowExecution();
  }

  @Override
  public String getNamespace() {
    return namespace;
  }
}
