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

import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.Payloads;
import io.temporal.client.ActivityCanceledException;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.ActivityCompletionFailureException;
import io.temporal.client.ActivityNotExistsException;
import io.temporal.client.ActivityWorkerShutdownException;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.external.ManualActivityCompletionClientFactory;
import io.temporal.internal.external.ManualActivityCompletionClientFactoryImpl;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.workflow.Functions;
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

  private final Lock lock = new ReentrantLock();
  private final ManualActivityCompletionClientFactory manualCompletionClientFactory;
  private final Functions.Proc completionHandle;
  private final ScheduledExecutorService heartbeatExecutor;
  private final long heartbeatIntervalMillis;
  private final DataConverter dataConverter;
  private final Scope metricsScope;
  private final ActivityInfo info;
  private boolean useLocalManualCompletion;
  private boolean doNotCompleteOnReturn;
  private boolean hasOutstandingHeartbeat;
  private ActivityCompletionException lastException;
  private Optional<Object> lastDetails;
  private ScheduledFuture future;

  /** Create an ActivityExecutionContextImpl with the given attributes. */
  ActivityExecutionContextImpl(
      WorkflowServiceStubs service,
      String namespace,
      ActivityInfo info,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor,
      Functions.Proc completionHandle,
      Scope metricsScope,
      String identity) {
    this.dataConverter = dataConverter;
    this.metricsScope = metricsScope;
    this.info = info;
    this.heartbeatExecutor = heartbeatExecutor;
    this.heartbeatIntervalMillis =
        Math.min(
            (long) (0.8 * info.getHeartbeatTimeout().toMillis()), MAX_HEARTBEAT_INTERVAL_MILLIS);
    this.completionHandle = completionHandle;
    this.manualCompletionClientFactory =
        new ManualActivityCompletionClientFactoryImpl(
            service, namespace, identity, dataConverter, metricsScope);
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

  public void sendHeartbeatRequest(Object details) {
    ManualActivityCompletionClient completionClient =
        manualCompletionClientFactory.getClient(getTaskToken());
    try {
      completionClient.sendHeartbeatRequest(details);
      lastException = null;
    } catch (ActivityCanceledException e) {
      lastException = new ActivityCanceledException(info);
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
    lock.lock();
    try {
      doNotCompleteOnReturn = true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isDoNotCompleteOnReturn() {
    lock.lock();
    try {
      return doNotCompleteOnReturn;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean isUseLocalManualCompletion() {
    lock.lock();
    try {
      return useLocalManualCompletion;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public ManualActivityCompletionClient useLocalManualCompletion() {
    lock.lock();
    try {
      doNotCompleteOnReturn();
      useLocalManualCompletion = true;
      return new CompletionAwareManualCompletionClient(
          manualCompletionClientFactory.getClient(info.getTaskToken()), completionHandle);
    } finally {
      lock.unlock();
    }
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
