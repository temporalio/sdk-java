package io.temporal.internal.activity;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInfo;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatRequest;
import io.temporal.api.workflowservice.v1.RecordActivityTaskHeartbeatResponse;
import io.temporal.api.workflowservice.v1.RespondActivityTaskFailedRequest;
import io.temporal.client.*;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.TimeoutFailure;
import io.temporal.internal.client.ActivityClientHelper;
import io.temporal.internal.common.PayloadsTooLargeFailure;
import io.temporal.internal.concurrent.structured.CancelSource;
import io.temporal.internal.payload.limits.PayloadErrorLimits;
import io.temporal.internal.payload.limits.PayloadLimitViolation;
import io.temporal.internal.payload.limits.PayloadLimitViolationException;
import io.temporal.internal.payload.storage.ActivityStorageTargets;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.rpcretry.DefaultStubServiceOperationRpcRetryOptions;
import io.temporal.worker.MetricsType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ThreadSafe
class HeartbeatContextImpl implements HeartbeatContext {
  private static final class HeartbeatAbandonedException extends RuntimeException {
    HeartbeatAbandonedException() {
      super(null, null, false, false);
    }
  }

  private static final Logger log = LoggerFactory.getLogger(HeartbeatContextImpl.class);
  private static final long HEARTBEAT_RETRY_WAIT_MILLIS = 1000;
  // Buffer added to the heartbeat timeout to avoid racing with the server's own timeout tracking.
  private static final long DEFAULT_LOCAL_HEARTBEAT_TIMEOUT_BUFFER_MILLIS = 5000;
  static final String LOCAL_TIMEOUT_BUFFER_PROPERTY = "temporal.activity.localTimeoutBufferMs";

  static long getLocalHeartbeatTimeoutBufferMillis() {
    String val = System.getProperty(LOCAL_TIMEOUT_BUFFER_PROPERTY);
    if (val != null) {
      try {
        return Long.parseLong(val);
      } catch (NumberFormatException e) {
        log.warn("Invalid {} value: {}", LOCAL_TIMEOUT_BUFFER_PROPERTY, val);
      }
    }
    return DEFAULT_LOCAL_HEARTBEAT_TIMEOUT_BUFFER_MILLIS;
  }

  private final Lock lock = new ReentrantLock();

  private final WorkflowServiceStubs service;
  private final String namespace;
  private final ActivityInfo info;
  private final String identity;
  private final ScheduledExecutorService heartbeatExecutor;
  private final long heartbeatIntervalMillis;
  private final DataConverter dataConverter;
  private final DataConverter heartbeatDataConverter;
  private final @Nullable ExternalStorageRunner externalStorage;

  private final Scope metricsScope;
  private final Optional<Payloads> prevAttemptHeartbeatDetails;
  private final long heartbeatTimeoutMillis;
  private final long localHeartbeatTimeoutBufferMillis;
  private final PayloadErrorLimits payloadErrorLimits;
  private final GrpcRetryer grpcRetryer;
  private volatile @Nullable PayloadLimitViolation payloadLimitViolation;
  private final AtomicReference<RespondActivityTaskFailedRequest> pendingPayloadLimitReport =
      new AtomicReference<>();
  // Completes once that failure has been sent or given up on.
  private volatile @Nullable CompletableFuture<Void> payloadLimitReport;
  private volatile boolean taskReported;

  // turned into true on a reception of the first heartbeat
  private boolean receivedAHeartbeat = false;
  private Object lastDetails;
  private boolean hasOutstandingHeartbeat;
  private ScheduledFuture<?> scheduledHeartbeat;

  // Deadline (in nanos, from System.nanoTime()) by which a successful heartbeat must occur.
  // 0 means no local timeout is active.
  private long heartbeatTimeoutDeadlineNanos;
  private boolean heartbeatTimedOut;
  private boolean rejectNewHeartbeats;

  private volatile CompletableFuture<Void> outstandingOffloadAbandon;
  private final AtomicInteger pendingAbandons = new AtomicInteger();

  private ActivityCompletionException lastException;
  private final CancelSource<ActivityCanceledException> cancellationSource =
      new CancelSource<>(ActivityCanceledException::new);

  public HeartbeatContextImpl(
      WorkflowServiceStubs service,
      String namespace,
      ActivityInfo info,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor,
      Scope metricsScope,
      String identity,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval,
      @Nullable ExternalStorageRunner externalStorage,
      PayloadErrorLimits payloadErrorLimits) {
    this(
        service,
        namespace,
        info,
        dataConverter,
        heartbeatExecutor,
        metricsScope,
        identity,
        maxHeartbeatThrottleInterval,
        defaultHeartbeatThrottleInterval,
        externalStorage,
        payloadErrorLimits,
        getLocalHeartbeatTimeoutBufferMillis());
  }

  HeartbeatContextImpl(
      WorkflowServiceStubs service,
      String namespace,
      ActivityInfo info,
      DataConverter dataConverter,
      ScheduledExecutorService heartbeatExecutor,
      Scope metricsScope,
      String identity,
      Duration maxHeartbeatThrottleInterval,
      Duration defaultHeartbeatThrottleInterval,
      @Nullable ExternalStorageRunner externalStorage,
      PayloadErrorLimits payloadErrorLimits,
      long localHeartbeatTimeoutBufferMillis) {
    this.service = service;
    this.payloadErrorLimits = payloadErrorLimits;
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.metricsScope = metricsScope;
    this.dataConverter = dataConverter;
    this.externalStorage = externalStorage;
    this.heartbeatDataConverter = dataConverter.withContext(heartbeatContext(namespace, info));
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
    this.heartbeatTimeoutMillis = info.getHeartbeatTimeout().toMillis();
    this.localHeartbeatTimeoutBufferMillis = localHeartbeatTimeoutBufferMillis;
    if (this.heartbeatTimeoutMillis > 0) {
      this.heartbeatTimeoutDeadlineNanos = computeHeartbeatTimeoutDeadlineNanos();
    }
  }

  /**
   * @see ActivityExecutionContext#heartbeat(Object)
   */
  @Override
  public <V> void heartbeat(V details) throws ActivityCompletionException {
    if (heartbeatExecutor.isShutdown()) {
      throw new ActivityWorkerShutdownException(info);
    }
    requestOffloadAbandon();
    lock.lock();
    pendingAbandons.decrementAndGet();
    try {
      checkHeartbeatTimeoutDeadlineLocked();
      if (rejectNewHeartbeats) {
        throw new IllegalStateException("Cannot record heartbeats after async activity completion");
      }
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
      cancellationSource.token().throwIfCancellationRequested();
    } finally {
      lock.unlock();
      reportPendingPayloadLimitFailure();
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
            heartbeatDataConverter.fromPayloads(
                0, prevAttemptHeartbeatDetails, detailsClass, detailsGenericType));
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * @see ActivityExecutionContext#getLastHeartbeatDetails(Class, Type)
   */
  @Override
  @SuppressWarnings("unchecked")
  public <V> Optional<V> getLastHeartbeatDetails(Class<V> detailsClass, Type detailsGenericType) {
    lock.lock();
    try {
      return Optional.ofNullable(
          heartbeatDataConverter.fromPayloads(
              0, prevAttemptHeartbeatDetails, detailsClass, detailsGenericType));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Object getLatestHeartbeatDetails() {
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

  @Override
  public void cancelOutstandingHeartbeat() {
    requestOffloadAbandon();
    lock.lock();
    pendingAbandons.decrementAndGet();
    try {
      if (scheduledHeartbeat != null) {
        scheduledHeartbeat.cancel(false);
        scheduledHeartbeat = null;
      }
      heartbeatTimeoutDeadlineNanos = 0;
      hasOutstandingHeartbeat = false;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void cancelFromWorkerCommand() {
    requestOffloadAbandon();
    lock.lock();
    pendingAbandons.decrementAndGet();
    try {
      requestCancelLocked();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void asyncCompletionStarted() {
    lock.lock();
    try {
      rejectNewHeartbeats = true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public CancellationToken<ActivityCanceledException> getCancellationToken() {
    return cancellationSource.token();
  }

  private void doHeartBeatLocked(Object details) {
    long nextHeartbeatDelay;
    try {
      // An activity failed for an oversized heartbeat sends no more heartbeats: each would fail
      // the activity again. The violation is checked again after the send, because the send is
      // what finds the first one.
      if (payloadLimitViolation == null) {
        sendHeartbeatRequest(details);
      }
      if (payloadLimitViolation != null) {
        hasOutstandingHeartbeat = false;
        scheduledHeartbeat = null;
        return;
      }
      hasOutstandingHeartbeat = false;
      nextHeartbeatDelay = heartbeatIntervalMillis;
      // Reset the local heartbeat timeout deadline only on successful send.
      // If sends keep failing, the next heartbeat() call after the deadline will cancel the
      // activity.
      if (heartbeatTimeoutDeadlineNanos != 0) {
        heartbeatTimeoutDeadlineNanos = computeHeartbeatTimeoutDeadlineNanos();
      }
    } catch (HeartbeatAbandonedException e) {
      scheduledHeartbeat = null;
      return;
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
                reportPendingPayloadLimitFailure();
              }
            },
            delay,
            TimeUnit.MILLISECONDS);
  }

  private long computeHeartbeatTimeoutDeadlineNanos() {
    return System.nanoTime()
        + TimeUnit.MILLISECONDS.toNanos(heartbeatTimeoutMillis + localHeartbeatTimeoutBufferMillis);
  }

  private void checkHeartbeatTimeoutDeadlineLocked() {
    if (heartbeatTimedOut) {
      throw new ActivityCanceledException(
          info, new TimeoutFailure(null, null, TimeoutType.TIMEOUT_TYPE_HEARTBEAT));
    }
    if (heartbeatTimeoutDeadlineNanos != 0 && System.nanoTime() >= heartbeatTimeoutDeadlineNanos) {
      heartbeatTimedOut = true;
      log.warn(
          "Activity heartbeat timed out locally. ActivityId={}, activityType={}",
          info.getActivityId(),
          info.getActivityType());
      throw new ActivityCanceledException(
          info, new TimeoutFailure(null, null, TimeoutType.TIMEOUT_TYPE_HEARTBEAT));
    }
  }

  private StorageDriverTargetInfo activityStorageTarget() {
    return storageTargetForActivity(namespace, info);
  }

  /**
   * Returns the handler's Nexus context for a standalone activity started from a Nexus operation
   * handler, and the activity context otherwise.
   */
  static SerializationContext heartbeatContext(String namespace, ActivityInfo info) {
    NexusSerializationContext nexusContext = info.getNexusSerializationContext();
    if (nexusContext != null) {
      return nexusContext;
    }
    return new ActivitySerializationContext(
        namespace,
        info.getWorkflowId(),
        info.getWorkflowType(),
        info.getActivityType(),
        info.getActivityTaskQueue(),
        info.isLocal());
  }

  /**
   * Standalone activities target the activity; workflow activities target their workflow, matching
   * where {@link io.temporal.internal.worker.ActivityWorker} stores the activity task payloads. A
   * non-empty {@code activityRunId} marks a standalone activity.
   */
  static StorageDriverTargetInfo storageTargetForActivity(String namespace, ActivityInfo info) {
    return ActivityStorageTargets.newBuilder(namespace)
        .setActivity(info.getActivityId(), info.getActivityRunId(), info.getActivityType())
        .setWorkflow(
            Strings.emptyToNull(info.getWorkflowId()),
            Strings.emptyToNull(info.getWorkflowRunId()),
            info.getWorkflowType())
        .build();
  }

  /**
   * Offloads large heartbeat payloads aborting if the store call runs longer than the heartbeat
   * interval, if a newer heartbeat supersedes this one, or if the activity is cancelled.
   */
  private void offloadHeartbeat(RecordActivityTaskHeartbeatRequest.Builder builder) {
    CancelSource<CancellationException> offloadCancel =
        new CancelSource<>(CancellationException::new);
    CompletableFuture<Void> abandon = new CompletableFuture<>();
    CancellationToken.Registration onActivityCancel =
        cancellationSource
            .token()
            .onCancel(
                () -> {
                  offloadCancel.cancel();
                  abandon.complete(null);
                });
    outstandingOffloadAbandon = abandon;
    try {
      if (pendingAbandons.get() > 0) {
        throw new HeartbeatAbandonedException();
      }
      CompletableFuture<Void> store =
          externalStorage.storeAsync(builder, activityStorageTarget(), null, offloadCancel.token());
      CompletableFuture.anyOf(store, abandon).get(heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
      if (!store.isDone()) {
        offloadCancel.cancel();
        throw new HeartbeatAbandonedException();
      }
      store.get();
    } catch (TimeoutException e) {
      offloadCancel.cancel();
      throw new CancellationException(
          "External storage did not store the heartbeat details within the heartbeat interval");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      offloadCancel.cancel();
      CancellationException cancelled =
          new CancellationException("External storage store interrupted");
      cancelled.initCause(e);
      throw cancelled;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      Throwables.throwIfUnchecked(cause);
      throw new CompletionException(cause);
    } finally {
      outstandingOffloadAbandon = null;
      onActivityCancel.close();
    }
  }

  private void requestOffloadAbandon() {
    pendingAbandons.incrementAndGet();
    CompletableFuture<Void> outstanding = outstandingOffloadAbandon;
    if (outstanding != null) {
      outstanding.complete(null);
    }
  }

  private void sendHeartbeatRequest(Object details) {
    try {
      RecordActivityTaskHeartbeatRequest.Builder builder =
          RecordActivityTaskHeartbeatRequest.newBuilder()
              .setTaskToken(ByteString.copyFrom(info.getTaskToken()))
              .setNamespace(namespace)
              .setIdentity(identity);
      heartbeatDataConverter.toPayloads(details).ifPresent(builder::setDetails);
      if (externalStorage != null) {
        offloadHeartbeat(builder);
      }
      RecordActivityTaskHeartbeatRequest request = builder.build();
      RecordActivityTaskHeartbeatResponse status =
          ActivityClientHelper.sendHeartbeatRequest(
              service, request, metricsScope, payloadErrorLimits);
      if (status.getCancelRequested()) {
        requestCancelLocked();
      } else if (status.getActivityReset()) {
        lastException = new ActivityResetException(info);
      } else if (status.getActivityPaused()) {
        lastException = new ActivityPausedException(info);
      } else {
        lastException = null;
      }
    } catch (StatusRuntimeException e) {
      Optional<PayloadLimitViolation> violation = PayloadLimitViolationException.find(e);
      if (violation.isPresent()) {
        failActivityWithPayloadsTooLarge(violation.get());
        // Cancellation is how the running activity learns that it was failed.
        requestCancelLocked();
      } else if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
        lastException = new ActivityNotExistsException(info, e);
      } else if (e.getStatus().getCode() == Status.Code.INVALID_ARGUMENT
          || e.getStatus().getCode() == Status.Code.FAILED_PRECONDITION) {
        lastException = new ActivityCompletionFailureException(info, e);
      } else {
        throw e;
      }
    }
  }

  /**
   * Queues the failure for a heartbeat over the payload error limits. {@link
   * #reportPendingPayloadLimitFailure} sends it once the lock is released, so that retrying the
   * call does not hold the lock.
   */
  private void failActivityWithPayloadsTooLarge(PayloadLimitViolation violation) {
    PayloadsTooLargeFailure.count(metricsScope, MetricsType.ACTIVITY_EXEC_FAILED_COUNTER);
    // These are set before cancellation is requested, so an activity that sees the cancellation
    // also sees how its outcome must be reported.
    payloadLimitReport = new CompletableFuture<>();
    pendingPayloadLimitReport.set(
        RespondActivityTaskFailedRequest.newBuilder()
            .setTaskToken(ByteString.copyFrom(info.getTaskToken()))
            .setNamespace(namespace)
            .setIdentity(identity)
            .setFailure(
                dataConverterWithActivityContext.exceptionToFailure(
                    PayloadsTooLargeFailure.of(violation)))
            .build());
    payloadLimitViolation = violation;
  }

  /** Sends the failure queued by an oversized heartbeat, if any. Called without the lock held. */
  private void reportPendingPayloadLimitFailure() {
    RespondActivityTaskFailedRequest request = pendingPayloadLimitReport.getAndSet(null);
    if (request == null) {
      return;
    }
    try {
      grpcRetryer.retry(
          () ->
              service
                  .blockingStub()
                  .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                  .respondActivityTaskFailed(request),
          new GrpcRetryer.GrpcRetryerOptions(
              DefaultStubServiceOperationRpcRetryOptions.INSTANCE, null));
      taskReported = true;
    } catch (Exception e) {
      // The activity reports this failure itself when it finishes, instead of its own outcome.
      log.warn("Failed to fail activity after an oversized heartbeat", e);
    } finally {
      payloadLimitReport.complete(null);
    }
  }

  @Override
  public boolean awaitTaskReported() {
    CompletableFuture<Void> report = payloadLimitReport;
    if (report != null) {
      report.join();
    }
    return taskReported;
  }

  @Override
  @Nullable
  public PayloadLimitViolation getPayloadLimitViolation() {
    return payloadLimitViolation;
  }

  private void requestCancelLocked() {
    ActivityCanceledException exception = new ActivityCanceledException(info);
    lastException = exception;
    cancellationSource.cancel(exception);
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
