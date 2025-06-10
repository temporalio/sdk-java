package io.temporal.internal.worker;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.workflow.Functions;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PollScaleReportHandle is responsible for managing the scaling of pollers based on the scaling
 * feedback attached to the task by the server.
 */
@ThreadSafe
public class PollScaleReportHandle<T extends ScalingTask> implements Runnable {
  private static final Logger logger = LoggerFactory.getLogger(PollScaleReportHandle.class);
  private final int minPollerCount;
  private final int maxPollerCount;
  private int targetPollerCount;
  private final Functions.Proc1<Integer> scaleCallback;
  private boolean everSawScalingDecision;
  private int ingestedThisPeriod;
  private int ingestedLastPeriod;
  private boolean scaleUpAllowed;

  public PollScaleReportHandle(
      int minPollerCount,
      int maxPollerCount,
      int initialPollerCount,
      Functions.Proc1<Integer> scaleCallback) {
    this.minPollerCount = minPollerCount;
    this.maxPollerCount = maxPollerCount;
    this.targetPollerCount = initialPollerCount;
    this.scaleCallback = scaleCallback;
  }

  public synchronized void report(T task, Throwable e) {
    if (e != null) {
      if ((e instanceof StatusRuntimeException)) {
        StatusRuntimeException statusRuntimeException = (StatusRuntimeException) e;
        if (statusRuntimeException.getStatus().getCode() == Status.Code.RESOURCE_EXHAUSTED) {
          updateTarget((t) -> t / 2);
          return;
        }
      }
      updateTarget((t -> t - 1));
      return;
    }
    // Handle the task
    if (task != null) {
      ingestedThisPeriod += 1;
    }

    if (task != null && task.getScalingDecision() != null) {
      ScalingTask.ScalingDecision scalingDecision = task.getScalingDecision();
      everSawScalingDecision = true;
      int deltaSuggestion = scalingDecision.getPollRequestDeltaSuggestion();
      if (deltaSuggestion > 0) {
        if (scaleUpAllowed) {
          updateTarget((t -> t + deltaSuggestion));
        }
      } else if (deltaSuggestion < 0) {
        updateTarget((t -> t + deltaSuggestion));
      }

    } else if (task == null && everSawScalingDecision) {
      // We want to avoid scaling down on empty polls if the server has never made any
      // scaling decisions - otherwise we might never scale up again.
      updateTarget((t) -> t - 1);
    }
  }

  private void updateTarget(Functions.Func1<Integer, Integer> func) {
    Integer target = targetPollerCount;
    Integer newTarget = func.apply(target);
    if (newTarget < minPollerCount) {
      newTarget = minPollerCount;
    } else if (newTarget > maxPollerCount) {
      newTarget = maxPollerCount;
    }
    if (newTarget.equals(target)) {
      return;
    }
    targetPollerCount = newTarget;
    if (scaleCallback != null) {
      scaleCallback.apply(targetPollerCount);
    }
  }

  @Override
  public synchronized void run() {
    scaleUpAllowed = (double) ingestedThisPeriod >= (double) ingestedLastPeriod * 1.1;
    ingestedLastPeriod = ingestedThisPeriod;
    ingestedThisPeriod = 0;
  }
}
