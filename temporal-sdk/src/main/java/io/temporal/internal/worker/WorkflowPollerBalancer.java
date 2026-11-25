package io.temporal.internal.worker;

import java.util.Optional;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Coordinates autoscaling sticky and non-sticky poll admission before slot reservation.
 *
 * <p>Without an advertised slot maximum, additional polls of one type wait until the other type has
 * an active poll. Cache size is not used as a fallback because it does not bound Java slot
 * acquisition.
 *
 * <p>Counts only outstanding polls, not executing tasks or pending reservations. Like Core's
 * balancer, this policy does not guarantee that both queues always have an outstanding poll.
 */
@ThreadSafe
final class WorkflowPollerBalancer {
  private final Optional<Integer> maxSlots;
  private int stickyPolls;
  private int normalPolls;
  private int stickyTarget;
  private long stickyBacklog;
  private boolean normalPollingDisabled;

  WorkflowPollerBalancer(Optional<Integer> maxSlots, int initialStickyTarget) {
    this.maxSlots = maxSlots;
    this.stickyTarget = initialStickyTarget;
  }

  synchronized void awaitAdmission(boolean sticky) throws InterruptedException {
    while (!canPoll(sticky)) {
      wait();
    }
  }

  private boolean canPoll(boolean sticky) {
    boolean preferSticky =
        stickyBacklog > 1 && stickyBacklog > stickyPolls && stickyPolls < stickyTarget;
    if (sticky) {
      if (stickyPolls == 0) {
        return true;
      }
      if (stickyPolls >= stickyTarget) {
        return false;
      }
      if (!normalPollingDisabled && normalPolls == 0 && wouldPreventFirstPoll(stickyPolls)) {
        return false;
      }
      if (preferSticky) {
        return true;
      }
    } else {
      // Let a disabled normal poller reach poll(), which aborts without issuing an RPC.
      if (normalPollingDisabled || normalPolls == 0) {
        return true;
      }
      if (stickyPolls == 0 && wouldPreventFirstPoll(normalPolls)) {
        return false;
      }
      if (preferSticky) {
        return false;
      }
    }
    return !maxSlots.isPresent() || (long) stickyPolls + normalPolls < maxSlots.get();
  }

  private boolean wouldPreventFirstPoll(int activePolls) {
    return !maxSlots.isPresent() || activePolls + 1 >= maxSlots.get();
  }

  synchronized void pollStarted(boolean sticky) {
    if (sticky) {
      stickyPolls++;
    } else {
      normalPolls++;
    }
    notifyAll();
  }

  synchronized void pollFinished(boolean sticky) {
    if (sticky) {
      stickyPolls--;
    } else {
      normalPolls--;
    }
    notifyAll();
  }

  synchronized void recordStickyBacklog(long backlog) {
    stickyBacklog = backlog;
    notifyAll();
  }

  synchronized void setStickyTarget(int target) {
    stickyTarget = target;
    notifyAll();
  }

  synchronized void disableNormalPoll() {
    normalPollingDisabled = true;
    notifyAll();
  }
}
