package io.temporal.workflowstreams.internal;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflowstreams.PollInput;
import io.temporal.workflowstreams.PollResult;
import io.temporal.workflowstreams.SubscribeOptions;
import io.temporal.workflowstreams.WireItem;
import io.temporal.workflowstreams.WorkflowStreamConstants;
import io.temporal.workflowstreams.WorkflowStreamItem;
import io.temporal.workflowstreams.WorkflowStreamListener;
import io.temporal.workflowstreams.WorkflowStreamSubscriptionHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared long-poll engine behind both subscription APIs. It runs the blocking {@code
 * startUpdate(..., ACCEPTED)} admission call on a shared executor, then waits for the long-poll
 * outcome with {@code getResultAsync()}, so no thread is occupied while a poll is blocked on the
 * server — many subscriptions can share a small pool.
 *
 * <p>The engine is a chained state machine with at most one outstanding step at a time: every
 * listener callback runs on the executor, and the next step is only submitted by the current one
 * (or by the completion of a future it produced). That serializes callbacks with happens-before
 * ordering, so the mutable poll state needs no locks.
 *
 * <p>This class is public only for internal wiring; construct subscriptions through {@link
 * io.temporal.workflowstreams.WorkflowStreamClient} instead.
 */
public final class SubscriptionDriver implements WorkflowStreamSubscriptionHandle {
  private static final Logger log = LoggerFactory.getLogger(SubscriptionDriver.class);

  private final WorkflowClient client;
  private final String workflowId;
  private final WorkflowStub latestRunStub;
  private final List<String> topics;
  private final long pollCooldownMs;
  private final ScheduledExecutorService executor;
  private final WorkflowStreamListener listener;
  private final Consumer<SubscriptionDriver> onFinish;
  private final CompletableFuture<Void> doneFuture = new CompletableFuture<>();

  // Mutable poll state, owned by the single outstanding step (see class javadoc).
  private long offset;
  // The run the most recent poll's update was admitted to. Captured before waiting for the
  // update's outcome so that, if that run continues-as-new mid-poll (failing the outcome), we
  // still know which run to inspect to tell a rollover apart from a terminal end.
  private String polledRunId = "";

  private volatile boolean closed;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean finished = new AtomicBoolean();

  public SubscriptionDriver(
      WorkflowClient client,
      String workflowId,
      SubscribeOptions options,
      ScheduledExecutorService executor,
      WorkflowStreamListener listener,
      Consumer<SubscriptionDriver> onFinish) {
    this.client = client;
    this.workflowId = workflowId;
    this.latestRunStub = client.newUntypedWorkflowStub(workflowId);
    this.topics = options.getTopics();
    this.offset = options.getFromOffset();
    this.pollCooldownMs = options.getPollCooldown().toMillis();
    this.executor = executor;
    this.listener = listener;
    this.onFinish = onFinish;
  }

  /** Submits the first poll. Idempotent. */
  public void start() {
    if (started.compareAndSet(false, true)) {
      hop(this::poll);
    }
  }

  /**
   * Stops the subscription before the next poll; a poll already blocked on the server is not
   * interrupted, and its result is discarded. Completes the done future normally without calling
   * {@code onCompleted}. Idempotent.
   */
  @Override
  public void close() {
    closed = true;
    finishSilent();
  }

  @Override
  public CompletableFuture<Void> getDoneFuture() {
    return doneFuture;
  }

  private void poll() {
    if (closed) {
      finishSilent();
      return;
    }
    WorkflowUpdateHandle<PollResult> handle;
    try {
      // Wait only for ACCEPTED so startUpdate returns the handle (and its run id) as soon
      // as the update is admitted; getResultAsync then waits for the outcome. With a
      // COMPLETED wait stage a mid-poll continue-as-new would fail startUpdate without a
      // handle, losing the run id.
      UpdateOptions<PollResult> updateOptions =
          UpdateOptions.newBuilder(PollResult.class)
              .setUpdateName(WorkflowStreamConstants.POLL_UPDATE_NAME)
              .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
              .build();
      handle = latestRunStub.startUpdate(updateOptions, new PollInput(topics, offset));
      polledRunId = handle.getExecution().getRunId();
    } catch (RuntimeException e) {
      handleError(e);
      return;
    }
    // No thread is held while the long poll is in flight; the future completes on a gRPC or
    // common-pool thread, so hop back to the executor before touching state or the listener.
    handle
        .getResultAsync()
        .whenComplete(
            (result, failure) -> {
              if (failure == null) {
                hop(() -> deliver(result));
              } else {
                Throwable unwrapped = unwrap(failure);
                hop(() -> handleError(unwrapped));
              }
            });
  }

  private void deliver(PollResult result) {
    if (closed) {
      finishSilent();
      return;
    }
    List<WorkflowStreamItem> items = new ArrayList<>(result.items.size());
    for (WireItem item : result.items) {
      items.add(new WorkflowStreamItem(item.topic, PayloadWire.decode(item.data), item.offset));
    }
    offset = result.nextOffset;
    deliverFrom(items, 0, result.moreReady);
  }

  private void deliverFrom(List<WorkflowStreamItem> items, int start, boolean moreReady) {
    // Iterate (rather than recurse) over items whose stages complete immediately, so a large
    // batch of synchronous onNext calls cannot grow the stack.
    for (int i = start; ; i++) {
      if (closed) {
        finishSilent();
        return;
      }
      if (i == items.size()) {
        if (moreReady) {
          hop(this::poll);
        } else {
          schedule(this::poll, pollCooldownMs);
        }
        return;
      }
      CompletionStage<Void> stage;
      try {
        stage = listener.onNext(items.get(i));
      } catch (RuntimeException e) {
        finishError(e);
        return;
      }
      if (stage == null) {
        continue;
      }
      CompletableFuture<Void> future = null;
      try {
        future = stage.toCompletableFuture();
      } catch (UnsupportedOperationException ignored) {
        // Fall through to the generic whenComplete path.
      }
      if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
        continue;
      }
      int next = i + 1;
      stage.whenComplete(
          (v, failure) -> {
            if (failure == null) {
              hop(() -> deliverFrom(items, next, moreReady));
            } else {
              Throwable unwrapped = unwrap(failure);
              hop(() -> finishError(unwrapped));
            }
          });
      return;
    }
  }

  private void handleError(Throwable e) {
    if (closed) {
      finishSilent();
      return;
    }
    ApplicationFailure failure = findApplicationFailure(e);
    if (failure != null) {
      if (WorkflowStreamConstants.ERROR_TYPE_TRUNCATED_OFFSET.equals(failure.getType())) {
        // Fell behind truncation; restart from the beginning of whatever still exists.
        offset = 0;
        hop(this::poll);
        return;
      }
      if (WorkflowStreamConstants.ERROR_TYPE_STREAM_DRAINING.equals(failure.getType())) {
        // The workflow is detaching for continue-as-new. Back off and retry; the poll
        // lands on the successor run once the rollover completes (or the chain/terminal
        // checks below fire on a genuine end).
        schedule(this::poll, pollCooldownMs);
        return;
      }
    }
    // The workflow may have continued-as-new or completed between polls. Follow the chain,
    // exit cleanly on a terminal state, otherwise surface the error. describe() is a blocking
    // call, which is why error handling always runs on the executor.
    WorkflowExecutionStatus status = describePolledRun();
    if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW) {
      // Subsequent polls use the latest-run stub, addressing the successor automatically.
      hop(this::poll);
      return;
    }
    if (isTerminal(status)) {
      finishCompleted();
      return;
    }
    finishError(e);
  }

  /**
   * Describes the run the most recent poll was admitted to: a rolled-over run is closed with status
   * CONTINUED_AS_NEW, whereas the latest run would report RUNNING, so describing by run id is what
   * makes the rollover check fire. The successor run id is not needed — subsequent polls address
   * the latest run automatically. A blank run id (no poll has been admitted yet) falls back to
   * describing the latest run.
   */
  private WorkflowExecutionStatus describePolledRun() {
    try {
      WorkflowStub stub;
      if (polledRunId == null || polledRunId.isEmpty()) {
        stub = latestRunStub;
      } else {
        stub =
            client.newUntypedWorkflowStub(
                WorkflowTargetOptions.newBuilder()
                    .setWorkflowId(workflowId)
                    .setRunId(polledRunId)
                    .build());
      }
      return stub.describe().getStatus();
    } catch (RuntimeException e) {
      return WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_UNSPECIFIED;
    }
  }

  private void finishSilent() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    doneFuture.complete(null);
    onFinish.accept(this);
  }

  private void finishCompleted() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    try {
      listener.onCompleted();
    } catch (Throwable t) {
      log.error("workflowstreams: listener onCompleted threw", t);
    }
    doneFuture.complete(null);
    onFinish.accept(this);
  }

  private void finishError(Throwable e) {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    try {
      listener.onError(e);
    } catch (Throwable t) {
      log.error("workflowstreams: listener onError threw", t);
      e.addSuppressed(t);
    }
    doneFuture.completeExceptionally(e);
    onFinish.accept(this);
  }

  /**
   * Submits the next step, never letting an unexpected step failure escape onto (and be swallowed
   * by) a pool thread.
   */
  private void hop(Runnable step) {
    try {
      executor.execute(() -> runStep(step));
    } catch (RejectedExecutionException e) {
      handleRejection(e);
    }
  }

  private void schedule(Runnable step, long delayMs) {
    try {
      executor.schedule(() -> runStep(step), delayMs, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      handleRejection(e);
    }
  }

  private void runStep(Runnable step) {
    try {
      step.run();
    } catch (Throwable t) {
      finishError(t);
    }
  }

  /**
   * A rejection while closed is the normal path during client shutdown; otherwise the caller shut a
   * user-supplied executor down early, which ends the subscription. Running the callback inline
   * preserves serialization: the rejected task was the only pending step.
   */
  private void handleRejection(RejectedExecutionException e) {
    if (closed) {
      finishSilent();
    } else {
      finishError(e);
    }
  }

  /** Strips {@code CompletionException}/{@code ExecutionException} wrappers added by futures. */
  private static Throwable unwrap(Throwable e) {
    while ((e instanceof CompletionException || e instanceof ExecutionException)
        && e.getCause() != null) {
      e = e.getCause();
    }
    return e;
  }

  private static boolean isTerminal(WorkflowExecutionStatus status) {
    switch (status) {
      case WORKFLOW_EXECUTION_STATUS_COMPLETED:
      case WORKFLOW_EXECUTION_STATUS_FAILED:
      case WORKFLOW_EXECUTION_STATUS_CANCELED:
      case WORKFLOW_EXECUTION_STATUS_TERMINATED:
      case WORKFLOW_EXECUTION_STATUS_TIMED_OUT:
        return true;
      default:
        return false;
    }
  }

  private static ApplicationFailure findApplicationFailure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof ApplicationFailure) {
        return (ApplicationFailure) t;
      }
    }
    return null;
  }
}
