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

import static io.temporal.internal.metrics.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.client.ActivityCancelledException;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.ActivityCompletionFailureException;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.OptionsUtils;
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
  private final ActivityInfo info;
  private final DataConverter dataConverter;
  private boolean doNotCompleteOnReturn;
  private final long heartbeatIntervalMillis;
  private Optional<Object> lastDetails;
  private boolean hasOutstandingHeartbeat;
  private final ScheduledExecutorService heartbeatExecutor;
  private final Scope metricsScope;
  private Lock lock = new ReentrantLock();
  private ScheduledFuture future;
  private ActivityCompletionException lastException;

  /** Create an ActivityExecutionContextImpl with the given attributes. */
  ActivityExecutionContextImpl(
      WorkflowServiceStubs service,
      String namespace,
      ActivityInfo info,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor,
      Scope metricsScope) {
    this.namespace = namespace;
    this.service = service;
    this.info = info;
    this.dataConverter = dataConverter;
    this.heartbeatIntervalMillis =
        Math.min(
            (long) (0.8 * info.getHeartbeatTimeout().toMillis()), MAX_HEARTBEAT_INTERVAL_MILLIS);
    this.heartbeatExecutor = heartbeatExecutor;
    this.metricsScope = metricsScope;
  }

  /** @see ActivityExecutionContext#heartbeat(Object) */
  @Override
  public <V> void heartbeat(V details) throws ActivityCompletionException {
    if (heartbeatExecutor.isShutdown()) {
      throw new ActivityWorkerShutdownException(info);
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
  public <V> Optional<V> getHeartbeatDetails(Class<V> detailsClass) {
    return getHeartbeatDetails(detailsClass, detailsClass);
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
      Optional<Payloads> details = info.getHeartbeatDetails();
      return Optional.ofNullable(dataConverter.fromPayloads(0, details, detailsClass, detailsType));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public byte[] getTaskToken() {
    return info.getTaskToken();
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
            .setTaskToken(OptionsUtils.toByteString(info.getTaskToken()));
    Optional<Payloads> payloads = dataConverter.toPayloads(details);
    if (payloads.isPresent()) {
      r.setDetails(payloads.get());
    }
    RecordActivityTaskHeartbeatResponse status;
    try {
      status =
          service
              .blockingStub()
              .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
              .recordActivityTaskHeartbeat(r.build());
      if (status.getCancelRequested()) {
        lastException = new ActivityCancelledException(info);
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

  @Override
  public void doNotCompleteOnReturn() {
    doNotCompleteOnReturn = true;
  }

  @Override
  public boolean isDoNotCompleteOnReturn() {
    return doNotCompleteOnReturn;
  }

  @Override
  public Scope getMetricsScope() {
    return metricsScope;
  }

  /** @see ActivityExecutionContext#getInfo() */
  @Override
  public ActivityInfo getInfo() {
    return info;
  }
}
