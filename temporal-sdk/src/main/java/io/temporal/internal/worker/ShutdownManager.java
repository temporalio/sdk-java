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

package io.temporal.internal.worker;

import static io.temporal.internal.common.GrpcUtils.isChannelShutdownException;

import com.google.common.util.concurrent.ListenableFuture;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.workflowservice.v1.ShutdownWorkerResponse;
import java.io.Closeable;
import java.time.Duration;
import java.util.concurrent.*;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShutdownManager implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(ShutdownManager.class);

  private final ScheduledExecutorService scheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor(
          new ExecutorThreadFactory(
              WorkerThreadsNameHelper.SHUTDOWN_MANAGER_THREAD_NAME_PREFIX, null));

  private static final int CHECK_PERIOD_MS = 250;

  /** executorToShutdown.shutdownNow() -&gt; timed wait for a graceful termination */
  public CompletableFuture<Void> shutdownExecutorNow(
      ExecutorService executorToShutdown, String executorName, Duration timeout) {
    executorToShutdown.shutdownNow();
    return limitedWait(executorToShutdown, executorName, timeout);
  }

  /** executorToShutdown.shutdownNow() -&gt; unlimited wait for termination */
  public CompletableFuture<Void> shutdownExecutorNowUntimed(
      ExecutorService executorToShutdown, String executorName) {
    executorToShutdown.shutdownNow();
    return untimedWait(executorToShutdown, executorName);
  }

  /**
   * executorToShutdown.shutdown() -&gt; timed wait for graceful termination -&gt;
   * executorToShutdown.shutdownNow()
   */
  public CompletableFuture<Void> shutdownExecutor(
      ExecutorService executorToShutdown, String executorName, Duration timeout) {
    executorToShutdown.shutdown();
    return limitedWait(executorToShutdown, executorName, timeout);
  }

  /** executorToShutdown.shutdown() -&gt; unlimited wait for graceful termination */
  public CompletableFuture<Void> shutdownExecutorUntimed(
      ExecutorService executorToShutdown, String executorName) {
    executorToShutdown.shutdown();
    return untimedWait(executorToShutdown, executorName);
  }

  public CompletableFuture<Void> waitForSupplierPermitsReleasedUnlimited(
      TrackingSlotSupplier<?> slotSupplier, String name) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    scheduledExecutorService.submit(new SlotSupplierDelayShutdown(slotSupplier, name, future));
    return future;
  }

  /**
   * waitForStickyQueueBalancer -&gt; disableNormalPoll -&gt; timed wait for graceful completion of
   * sticky workflows
   */
  public CompletableFuture<Void> waitForStickyQueueBalancer(
      StickyQueueBalancer balancer, Duration timeout) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    balancer.disableNormalPoll();
    scheduledExecutorService.schedule(
        () -> {
          future.complete(null);
        },
        timeout.toMillis(),
        TimeUnit.MILLISECONDS);
    return future;
  }

  /**
   * Wait for {@code executorToShutdown} to terminate. Only completes the returned CompletableFuture
   * when the executor is terminated.
   */
  private CompletableFuture<Void> untimedWait(
      ExecutorService executorToShutdown, String executorName) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    scheduledExecutorService.submit(
        new ExecutorReportingDelayShutdown(executorToShutdown, executorName, future));
    return future;
  }

  /**
   * Wait for {@code executorToShutdown} to terminate for a defined interval, shutdownNow after
   * that. Always completes the returned CompletableFuture on termination of the executor or on a
   * timeout, whatever happens earlier.
   */
  private CompletableFuture<Void> limitedWait(
      ExecutorService executorToShutdown, String executorName, Duration timeout) {
    int attempts = (int) Math.ceil((double) timeout.toMillis() / CHECK_PERIOD_MS);

    CompletableFuture<Void> future = new CompletableFuture<>();
    scheduledExecutorService.submit(
        new ExecutorLimitedWaitShutdown(executorToShutdown, attempts, executorName, future));
    return future;
  }

  /**
   * Wait for {@code shutdownRequest} to finish. shutdownRequest is considered best effort, so we do
   * not fail the shutdown if it fails.
   */
  public CompletableFuture<Void> waitOnWorkerShutdownRequest(
      ListenableFuture<ShutdownWorkerResponse> shutdownRequest) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    shutdownRequest.addListener(
        () -> {
          try {
            shutdownRequest.get();
          } catch (Exception e) {
            if (e instanceof ExecutionException) {
              e = (Exception) e.getCause();
            }
            if (e instanceof StatusRuntimeException) {
              // If the server does not support shutdown, ignore the exception
              if (Status.Code.UNIMPLEMENTED.equals(
                      ((StatusRuntimeException) e).getStatus().getCode())
                  || isChannelShutdownException((StatusRuntimeException) e)) {
                return;
              }
            }
            log.warn("failed to call shutdown worker", e);
          } finally {
            future.complete(null);
          }
        },
        scheduledExecutorService);
    return future;
  }

  @Override
  public void close() {
    scheduledExecutorService.shutdownNow();
  }

  private abstract class LimitedWaitShutdown implements Runnable {
    private final CompletableFuture<Void> promise;
    private final int maxAttempts;
    private int attempt;

    public LimitedWaitShutdown(int maxAttempts, CompletableFuture<Void> promise) {
      this.promise = promise;
      this.maxAttempts = maxAttempts;
    }

    @Override
    public void run() {
      if (isTerminated()) {
        onSuccessfulTermination();
        promise.complete(null);
        return;
      }
      attempt++;
      if (attempt > maxAttempts) {
        onAttemptExhaustion();
        // we don't want to complicate shutdown with dealing of exceptions and errors of all sorts,
        // so just log and complete the promise
        promise.complete(null);
        return;
      }
      scheduledExecutorService.schedule(this, CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    abstract boolean isTerminated();

    abstract void onAttemptExhaustion();

    abstract void onSuccessfulTermination();
  }

  private class ExecutorLimitedWaitShutdown extends LimitedWaitShutdown {
    private final ExecutorService executorToShutdown;
    private final String executorName;

    public ExecutorLimitedWaitShutdown(
        ExecutorService executorToShutdown,
        int maxAttempts,
        String executorName,
        CompletableFuture<Void> promise) {
      super(maxAttempts, promise);
      this.executorToShutdown = executorToShutdown;
      this.executorName = executorName;
    }

    @Override
    boolean isTerminated() {
      return executorToShutdown.isTerminated();
    }

    @Override
    void onAttemptExhaustion() {
      log.warn(
          "Wait for a graceful shutdown of {} timed out, fallback to shutdownNow()", executorName);
      executorToShutdown.shutdownNow();
    }

    @Override
    void onSuccessfulTermination() {}
  }

  private abstract class ReportingDelayShutdown implements Runnable {
    // measures in attempts count, not in ms
    private static final int BLOCKED_REPORTING_THRESHOLD = 60;
    private static final int BLOCKED_REPORTING_PERIOD = 20;

    private final CompletableFuture<Void> promise;
    private int attempt;

    public ReportingDelayShutdown(CompletableFuture<Void> promise) {
      this.promise = promise;
    }

    @Override
    public void run() {
      if (isTerminated()) {
        if (attempt > BLOCKED_REPORTING_THRESHOLD) {
          onSlowSuccessfulTermination();
        } else {
          onSuccessfulTermination();
        }
        promise.complete(null);
        return;
      }
      attempt++;
      // log a problem after BLOCKED_REPORTING_THRESHOLD attempts only
      if (attempt >= BLOCKED_REPORTING_THRESHOLD) {
        // and repeat every BLOCKED_REPORTING_PERIOD attempts
        if (((float) (attempt - BLOCKED_REPORTING_THRESHOLD) % BLOCKED_REPORTING_PERIOD) < 0.001) {
          onSlowTermination();
        }
      }
      scheduledExecutorService.schedule(this, CHECK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    abstract boolean isTerminated();

    abstract void onSlowTermination();

    abstract void onSuccessfulTermination();

    /** Called only if {@link #onSlowTermination()} was called before */
    abstract void onSlowSuccessfulTermination();
  }

  private class ExecutorReportingDelayShutdown extends ReportingDelayShutdown {
    private final ExecutorService executorToShutdown;
    private final String executorName;

    public ExecutorReportingDelayShutdown(
        ExecutorService executorToShutdown, String executorName, CompletableFuture<Void> promise) {
      super(promise);
      this.executorToShutdown = executorToShutdown;
      this.executorName = executorName;
    }

    @Override
    boolean isTerminated() {
      return executorToShutdown.isTerminated();
    }

    @Override
    void onSlowTermination() {
      log.warn(
          "Graceful shutdown of {} is blocked by one of the long currently processing tasks",
          executorName);
    }

    @Override
    void onSuccessfulTermination() {}

    @Override
    void onSlowSuccessfulTermination() {
      log.warn("{} successfully terminated", executorName);
    }
  }

  private class SlotSupplierDelayShutdown extends ReportingDelayShutdown {
    private final TrackingSlotSupplier<?> slotSupplier;
    private final String name;

    public SlotSupplierDelayShutdown(
        TrackingSlotSupplier<?> supplier, String name, CompletableFuture<Void> promise) {
      super(promise);
      this.slotSupplier = supplier;
      this.name = name;
    }

    @Override
    boolean isTerminated() {
      return slotSupplier.getIssuedSlots() == 0;
    }

    @Override
    void onSlowTermination() {
      log.warn("Wait for release of slots of {} takes a long time", name);
    }

    @Override
    void onSuccessfulTermination() {}

    @Override
    void onSlowSuccessfulTermination() {
      log.warn("All slots of {} were successfully released", name);
    }
  }

  public static long awaitTermination(@Nullable ExecutorService s, long timeoutMillis) {
    if (s == null) {
      return timeoutMillis;
    }
    return runAndGetRemainingTimeoutMs(
        timeoutMillis,
        () -> {
          try {
            boolean ignored = s.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  public static long runAndGetRemainingTimeoutMs(long initialTimeoutMs, Runnable toRun) {
    long startedNs = System.nanoTime();
    try {
      toRun.run();
    } catch (Throwable e) {
      log.warn("Exception during waiting for termination", e);
    }
    long remainingTimeoutMs =
        initialTimeoutMs - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);
    return remainingTimeoutMs < 0 ? 0 : remainingTimeoutMs;
  }

  public static long awaitTermination(@Nullable Shutdownable s, long timeoutMillis) {
    if (s == null) {
      return timeoutMillis;
    }
    return runAndGetRemainingTimeoutMs(
        timeoutMillis, () -> s.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS));
  }
}
