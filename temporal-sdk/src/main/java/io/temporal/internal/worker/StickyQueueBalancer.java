package io.temporal.internal.worker;

import io.temporal.api.enums.v1.TaskQueueKind;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class StickyQueueBalancer {
  private final int pollersCount;
  private final boolean stickyQueueEnabled;
  private int stickyPollers = 0;
  private int normalPollers = 0;
  private boolean disableNormalPoll = false;
  private long stickyBacklogSize = 0;

  public StickyQueueBalancer(int pollersCount, boolean stickyQueueEnabled) {
    this.pollersCount = pollersCount;
    this.stickyQueueEnabled = stickyQueueEnabled;
  }

  /**
   * @return task queue kind that should be used for the next poll
   */
  public synchronized TaskQueueKind makePoll() {
    if (stickyQueueEnabled) {
      if (disableNormalPoll) {
        stickyPollers++;
        return TaskQueueKind.TASK_QUEUE_KIND_STICKY;
      }
      // If pollersCount >= stickyBacklogSize > 0 we want to go back to a normal ratio to avoid a
      // situation that too many pollers (all of them in the worst case) will open only sticky queue
      // polls observing a stickyBacklogSize == 1 for example (which actually can be 0 already at
      // that moment) and get stuck causing dip in worker load.
      if (stickyBacklogSize > pollersCount || stickyPollers <= normalPollers) {
        stickyPollers++;
        return TaskQueueKind.TASK_QUEUE_KIND_STICKY;
      }
    }
    normalPollers++;
    return TaskQueueKind.TASK_QUEUE_KIND_NORMAL;
  }

  /**
   * @param taskQueueKind what kind of task queue poll was just finished
   */
  public synchronized void finishPoll(TaskQueueKind taskQueueKind) {
    switch (taskQueueKind) {
      case TASK_QUEUE_KIND_NORMAL:
        normalPollers--;
        break;
      case TASK_QUEUE_KIND_STICKY:
        stickyPollers--;
        break;
      default:
        throw new IllegalArgumentException("Invalid task queue kind: " + taskQueueKind);
    }
  }

  /**
   * @param taskQueueKind what kind of task queue poll was just finished
   * @param backlogSize backlog size from the poll response, helps to determine if the sticky queue
   *     is backlogged
   */
  public synchronized void finishPoll(TaskQueueKind taskQueueKind, long backlogSize) {
    finishPoll(taskQueueKind);
    if (TaskQueueKind.TASK_QUEUE_KIND_STICKY.equals(taskQueueKind)) {
      stickyBacklogSize = backlogSize;
    }
  }

  public synchronized void disableNormalPoll() {
    disableNormalPoll = true;
  }
}
