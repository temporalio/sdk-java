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

package io.temporal.internal.activity;

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.client.*;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.client.ActivityClientHelper;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
class HeartbeatContextImpl implements HeartbeatContext {
  private static final Logger log = LoggerFactory.getLogger(HeartbeatContextImpl.class);
  private static final long HEARTBEAT_RETRY_WAIT_MILLIS = 1000;

  private final Lock lock = new ReentrantLock();

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final ActivityInfo info;
  private final String identity;
  private final ScheduledExecutorService heartbeatExecutor;
  private final long heartbeatIntervalMillis;
  private final DataConverter dataConverter;
  private final DataConverter dataConverterWithActivityContext;

  private final Scope metricsScope;
  private final Optional<Payloads> prevAttemptHeartbeatDetails;

  // turned into true on a reception of the first heartbeat
  private boolean receivedAHeartbeat = false;
  private Object lastDetails;
  private boolean hasOutstandingHeartbeat;
  private ScheduledFuture<?> scheduledHeartbeat;

  private ActivityCompletionException lastException;

  public HeartbeatContextImpl(
      WorkflowServiceStubs service,
      String namespace,
      ActivityInfo info,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor,
      Scope metricsScope,
      String identity,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval) {
    this.service = service;
    this.metricsScope = metricsScope;
    this.dataConverter = dataConverter;
    this.dataConverterWithActivityContext =
        dataConverter.withContext(
            new ActivitySerializationContext(
                namespace,
                info.getWorkflowId(),
                info.getWorkflowType(),
                info.getActivityType(),
                info.getActivityTaskQueue(),
                info.isLocal()));
    this.namespace = namespace;
    this.info = info;
    this.identity = identity;
    this.prevAttemptHeartbeatDetails = info.getHeartbeatDetails();
    this.heartbeatExecutor = heartbeatExecutor;
    this.heartbeatIntervalMillis =
        getHeartbeatIntervalMs(
            info.getHeartbeatTimeout(),
            maxHeartbeatThrottleInterval,
            defaultHeartbeatThrottleInterval);
  }

  /**
   * @see ActivityExecutionContext#heartbeat(Object)
   */
  @Override
  public <V> void heartbeat(V details) throws ActivityCompletionException {
    if (heartbeatExecutor.isShutdown()) {
      throw new ActivityWorkerShutdownException(info);
    }
    lock.lock();
    try {
      receivedAHeartbeat = true;
      lastDetails = details;
      hasOutstandingHeartbeat = true;
      // Only do sync heartbeat if there is no such call scheduled.
      if (scheduledHeartbeat == null) {
        doHeartBeatLocked(details);
      }
      if (lastException != null) {
        throw lastException;
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * @see ActivityExecutionContext#getHeartbeatDetails(Class, Type)
   */
  @Override
  @SuppressWarnings("unchecked")
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType) {
    lock.lock();
    try {
      if (receivedAHeartbeat) {
        return Optional.ofNullable((V) this.lastDetails);
      } else {
        return Optional.ofNullable(
            dataConverterWithActivityContext.fromPayloads(
                0, prevAttemptHeartbeatDetails, detailsClass, detailsGenericType));
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Object getLastHeartbeatDetails() {
    lock.lock();
    try {
      if (receivedAHeartbeat) {
        return this.lastDetails;
      }
      return null;
    } finally {
      lock.unlock();
    }
  }

  private void doHeartBeatLocked(Object details) {
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

    scheduleNextHeartbeatLocked(nextHeartbeatDelay);
  }

  private void scheduleNextHeartbeatLocked(long delay) {
    scheduledHeartbeat =
        heartbeatExecutor.schedule(
            () -> {
              lock.lock();
              try {
                if (hasOutstandingHeartbeat) {
                  doHeartBeatLocked(lastDetails);
                } else {
                  // if no new heartbeats have been submitted in the previous time interval, we
                  // don't need to throttle
                  // and the next heartbeat should go immediately without following a schedule.
                  scheduledHeartbeat = null;
                }
              } finally {
                lock.unlock();
              }
            },
            delay,
            TimeUnit.MILLISECONDS);
  }

  private void sendHeartbeatRequest(Object details) {
    try {
      RecordActivityTaskHeartbeatResponse status =
          ActivityClientHelper.sendHeartbeatRequest(
              service,
              namespace,
              identity,
              info.getTaskToken(),
              dataConverterWithActivityContext.toPayloads(details),
              metricsScope);
      if (status.getCancelRequested()) {
        lastException = new ActivityCanceledException(info);
      } else {
        lastException = null;
      }
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        lastException = new ActivityNotExistsException(info, e);
      } else if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT
          || e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
        lastException = new ActivityCompletionFailureException(info, e);
      } else {
        throw e;
      }
    }
  }

  private static long getHeartbeatIntervalMs(
      Duration activityHeartbeatTimeout,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval) {
    long interval =
        activityHeartbeatTimeout.isZero()
            ? defaultHeartbeatThrottleInterval.toMillis()
            : (long) (0.8 * activityHeartbeatTimeout.toMillis());
    return Math.min(interval, maxHeartbeatThrottleInterval.toMillis());
  }
}
