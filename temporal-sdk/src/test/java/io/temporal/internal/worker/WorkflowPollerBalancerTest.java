package io.temporal.internal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.*;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class WorkflowPollerBalancerTest {
  @Test(timeout = 10000)
  public void stickyPreferenceRequiresMeaningfulBacklog() throws Exception {
    WorkflowPollerBalancer balancer = balancer(4, 3, 1, 1);
    for (long backlog : new long[] {0, 1}) {
      balancer.recordStickyBacklog(backlog);
      balancer.awaitAdmission(false);
      balancer.awaitAdmission(true);
    }
    balancer.recordStickyBacklog(10);
    balancer.awaitAdmission(true);
    assertBlocked(balancer, false);

    // A backlog no larger than the outstanding sticky polls is not meaningful.
    balancer.pollStarted(true);
    balancer.recordStickyBacklog(2);
    balancer.awaitAdmission(false);
  }

  @Test(timeout = 10000)
  public void reachingStickyTargetReleasesPreference() throws Exception {
    WorkflowPollerBalancer balancer = balancer(5, 3, 2, 1);
    balancer.recordStickyBacklog(10);
    assertUnblockedBy(balancer, false, () -> balancer.pollStarted(true));
    assertBlocked(balancer, true);
  }

  @Test(timeout = 10000)
  public void targetDecreaseWakesNormalWaiter() throws Exception {
    WorkflowPollerBalancer balancer = balancer(4, 3, 2, 1);
    balancer.recordStickyBacklog(10);
    assertUnblockedBy(balancer, false, () -> balancer.setStickyTarget(2));
    assertBlocked(balancer, true);
  }

  @Test(timeout = 10000)
  public void targetIncreaseWakesStickyWaiter() throws Exception {
    WorkflowPollerBalancer balancer = balancer(4, 1, 1, 1);
    assertUnblockedBy(balancer, true, () -> balancer.setStickyTarget(2));
  }

  @Test(timeout = 10000)
  public void backlogChangeWakesNormalWaiter() throws Exception {
    WorkflowPollerBalancer balancer = balancer(4, 3, 1, 1);
    balancer.recordStickyBacklog(10);
    assertUnblockedBy(balancer, false, () -> balancer.recordStickyBacklog(1));
  }

  @Test(timeout = 10000)
  public void pollCompletionWakesWaiterAtCapacity() throws Exception {
    WorkflowPollerBalancer balancer = balancer(4, 4, 2, 2);
    assertUnblockedBy(balancer, false, () -> balancer.pollFinished(true));
    balancer.pollStarted(true);
    assertUnblockedBy(balancer, true, () -> balancer.pollFinished(false));
  }

  @Test(timeout = 10000)
  public void firstPollOfEachTypeCanAttemptAdmission() throws Exception {
    for (int capacity : new int[] {1, 2, 4}) {
      WorkflowPollerBalancer balancer = balancer(capacity, 3, 0, 1);
      balancer.awaitAdmission(true);
      balancer = balancer(capacity, 3, 1, 0);
      balancer.recordStickyBacklog(10);
      balancer.awaitAdmission(false);
    }
  }

  @Test(timeout = 10000)
  public void reserveRoomForOtherQueuesFirstPoll() throws Exception {
    for (int capacity : new int[] {1, 2, 4}) {
      assertBlocked(balancer(capacity, 10, Math.max(1, capacity - 1), 0), true);
      assertBlocked(balancer(capacity, 10, 0, Math.max(1, capacity - 1)), false);
    }
  }

  @Test(timeout = 10000)
  public void missingOtherPollDoesNotBlockSpareCapacity() throws Exception {
    balancer(4, 3, 1, 0).awaitAdmission(true);
    balancer(4, 3, 0, 1).awaitAdmission(false);
  }

  @Test(timeout = 10000)
  public void stickyTargetPreventsAdmissionEvenWithSpareSlots() throws Exception {
    assertBlocked(balancer(10, 2, 2, 1), true);
    assertBlocked(balancer(10, 1, 2, 1), true);
  }

  @Test(timeout = 10000)
  public void unknownCapacityStillRespectsBacklogAndTarget() throws Exception {
    WorkflowPollerBalancer balancer = new WorkflowPollerBalancer(Optional.empty(), 2);
    balancer.pollStarted(true);
    balancer.pollStarted(false);
    balancer.recordStickyBacklog(10);
    assertBlocked(balancer, false);
    balancer.pollStarted(true);
    balancer.awaitAdmission(false);
    assertBlocked(balancer, true);
  }

  @Test(timeout = 10000)
  public void disablingNormalPollingReleasesCapacityForStickyDrain() throws Exception {
    WorkflowPollerBalancer balancer = balancer(2, 3, 1, 0);
    assertUnblockedBy(balancer, true, balancer::disableNormalPoll);
  }

  private static WorkflowPollerBalancer balancer(
      int slots, int target, int stickyPolls, int normalPolls) {
    WorkflowPollerBalancer balancer = new WorkflowPollerBalancer(Optional.of(slots), target);
    for (int i = 0; i < stickyPolls; i++) {
      balancer.pollStarted(true);
    }
    for (int i = 0; i < normalPolls; i++) {
      balancer.pollStarted(false);
    }
    return balancer;
  }

  private static void assertBlocked(WorkflowPollerBalancer balancer, boolean sticky)
      throws Exception {
    assertWaiter(balancer, sticky, null);
  }

  private static void assertUnblockedBy(
      WorkflowPollerBalancer balancer, boolean sticky, Runnable change) throws Exception {
    assertWaiter(balancer, sticky, change);
  }

  private static void assertWaiter(WorkflowPollerBalancer balancer, boolean sticky, Runnable change)
      throws Exception {
    FutureTask<Void> admission =
        new FutureTask<>(
            () -> {
              balancer.awaitAdmission(sticky);
              return null;
            });
    Thread thread = new Thread(admission, "balancer-test");
    thread.start();
    try {
      assertEventually(
          Duration.ofSeconds(5),
          () -> {
            assertFalse("Admission should be blocked", admission.isDone());
            assertEquals(Thread.State.WAITING, thread.getState());
          });
      if (change != null) {
        change.run();
        admission.get(5, TimeUnit.SECONDS);
      }
    } finally {
      thread.interrupt();
      thread.join(5000);
      assertFalse("Admission waiter should terminate on interrupt", thread.isAlive());
    }
  }
}
