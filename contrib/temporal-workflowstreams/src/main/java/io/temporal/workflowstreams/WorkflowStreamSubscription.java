package io.temporal.workflowstreams;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.client.WorkflowUpdateHandle;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.common.Experimental;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflowstreams.internal.PayloadWire;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A blocking, single-use subscription over a workflow stream, driven on the consuming thread. Each
 * poll long-polls the workflow's poll update; the subscription ends cleanly ({@code hasNext() ==
 * false}) when the workflow reaches a terminal state, and automatically follows continue-as-new
 * chains.
 *
 * <p>{@link #close} stops the subscription before the next poll; a poll already blocked on the
 * server is not interrupted.
 */
@Experimental
public final class WorkflowStreamSubscription
    implements Iterator<WorkflowStreamItem>, Iterable<WorkflowStreamItem>, AutoCloseable {
  private final WorkflowClient client;
  private final String workflowId;
  private final WorkflowStub latestRunStub;
  private final List<String> topics;
  private final long pollCooldownMs;

  private final Deque<WorkflowStreamItem> queue = new ArrayDeque<>();
  private long offset;
  private boolean done;
  private volatile boolean closed;
  private boolean cooldownBeforeNextPoll;
  // The run the most recent poll's update was admitted to. Captured before waiting for the
  // update's outcome so that, if that run continues-as-new mid-poll (failing the outcome), we
  // still know which run to inspect to tell a rollover apart from a terminal end.
  private String polledRunId = "";

  WorkflowStreamSubscription(WorkflowClient client, String workflowId, SubscribeOptions options) {
    this.client = client;
    this.workflowId = workflowId;
    this.latestRunStub = client.newUntypedWorkflowStub(workflowId);
    this.topics = options.getTopics();
    this.offset = options.getFromOffset();
    this.pollCooldownMs = options.getPollCooldown().toMillis();
  }

  /**
   * Returns this subscription; it is single-use, so iterate it at most once (typically with a
   * for-each loop).
   */
  @Override
  public Iterator<WorkflowStreamItem> iterator() {
    return this;
  }

  /**
   * Returns whether another item is available, long-polling the workflow until one is (or the
   * stream ends). Blocks on the consuming thread.
   */
  @Override
  public boolean hasNext() {
    while (queue.isEmpty() && !done) {
      if (closed) {
        done = true;
        break;
      }
      pollOnce();
    }
    return !queue.isEmpty();
  }

  @Override
  public WorkflowStreamItem next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    return queue.poll();
  }

  /** Stops the subscription before the next poll. */
  @Override
  public void close() {
    closed = true;
  }

  private void pollOnce() {
    if (cooldownBeforeNextPoll) {
      cooldownBeforeNextPoll = false;
      if (!sleepCooldown()) {
        return;
      }
    }

    try {
      // Wait only for ACCEPTED so startUpdate returns the handle (and its run id) as soon
      // as the update is admitted; getResult then waits for the outcome. With a COMPLETED
      // wait stage a mid-poll continue-as-new would fail startUpdate without a handle,
      // losing the run id.
      UpdateOptions<PollResult> updateOptions =
          UpdateOptions.newBuilder(PollResult.class)
              .setUpdateName(WorkflowStreamConstants.POLL_UPDATE_NAME)
              .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
              .build();
      WorkflowUpdateHandle<PollResult> handle =
          latestRunStub.startUpdate(updateOptions, new PollInput(topics, offset));
      polledRunId = handle.getExecution().getRunId();
      PollResult result = handle.getResult();

      for (WireItem item : result.items) {
        queue.add(new WorkflowStreamItem(item.topic, PayloadWire.decode(item.data), item.offset));
      }
      offset = result.nextOffset;
      cooldownBeforeNextPoll = !result.moreReady;
    } catch (RuntimeException e) {
      handlePollError(e);
    }
  }

  private void handlePollError(RuntimeException e) {
    ApplicationFailure failure = findApplicationFailure(e);
    if (failure != null) {
      if (WorkflowStreamConstants.ERROR_TYPE_TRUNCATED_OFFSET.equals(failure.getType())) {
        // Fell behind truncation; restart from the beginning of whatever still exists.
        offset = 0;
        return;
      }
      if (WorkflowStreamConstants.ERROR_TYPE_STREAM_DRAINING.equals(failure.getType())) {
        // The workflow is detaching for continue-as-new. Back off and retry; the poll
        // lands on the successor run once the rollover completes (or the chain/terminal
        // checks below fire on a genuine end).
        sleepCooldown();
        return;
      }
    }
    // The workflow may have continued-as-new or completed between polls. Follow the chain,
    // exit cleanly on a terminal state, otherwise surface the error.
    WorkflowExecutionStatus status = describePolledRun();
    if (status == WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW) {
      // Subsequent polls use the latest-run stub, addressing the successor automatically.
      return;
    }
    if (isTerminal(status)) {
      done = true;
      return;
    }
    throw e;
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

  /** Returns false if interrupted, after marking the subscription done. */
  private boolean sleepCooldown() {
    try {
      Thread.sleep(pollCooldownMs);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      done = true;
      return false;
    }
  }
}
