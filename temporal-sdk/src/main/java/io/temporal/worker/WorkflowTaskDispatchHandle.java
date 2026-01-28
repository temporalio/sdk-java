package io.temporal.worker;

import com.google.common.base.Preconditions;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.internal.worker.TrackingSlotSupplier;
import io.temporal.internal.worker.WorkflowTask;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseReason;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class WorkflowTaskDispatchHandle implements Closeable {
  private final AtomicBoolean completed = new AtomicBoolean();
  private final Function<WorkflowTask, Boolean> dispatchCallback;
  private final TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier;
  private final SlotPermit permit;
  private final WorkerDeploymentOptions deploymentOptions;

  /**
   * @param dispatchCallback callback into a {@code WorkflowWorker} to dispatch a workflow task.
   * @param slotSupplier slot supplier that was used to reserve a slot for this workflow task
   * @param permit the slot permit reserved for this workflow task
   * @param deploymentOptions deployment options of the worker that reserved the slot, or null if
   *     not configured
   */
  public WorkflowTaskDispatchHandle(
      DispatchCallback dispatchCallback,
      TrackingSlotSupplier<WorkflowSlotInfo> slotSupplier,
      SlotPermit permit,
      @Nullable WorkerDeploymentOptions deploymentOptions) {
    this.dispatchCallback = dispatchCallback;
    this.slotSupplier = slotSupplier;
    this.permit = permit;
    this.deploymentOptions = deploymentOptions;
  }

  /**
   * @param workflowTask to be fed directly into the workflow worker
   * @return true is the workflow task was successfully dispatched
   * @throws IllegalArgumentException if the workflow task doesn't belong to the task queue of the
   *     worker provided this {@link WorkflowTaskDispatchHandle}
   */
  public boolean dispatch(@Nonnull PollWorkflowTaskQueueResponse workflowTask) {
    Preconditions.checkNotNull(workflowTask, "workflowTask");
    if (completed.compareAndSet(false, true)) {
      return dispatchCallback.apply(
          new WorkflowTask(workflowTask, (rr) -> slotSupplier.releaseSlot(rr, permit)));
    } else {
      return false;
    }
  }

  /**
   * @return deployment options of the worker that reserved the slot, or null if not configured
   */
  @Nullable
  public WorkerDeploymentOptions getDeploymentOptions() {
    return deploymentOptions;
  }

  @Override
  public void close() {
    if (completed.compareAndSet(false, true)) {
      slotSupplier.releaseSlot(SlotReleaseReason.neverUsed(), permit);
    }
  }

  /** A callback into a {@code WorkflowWorker} to dispatch a workflow task */
  @FunctionalInterface
  public interface DispatchCallback extends Function<WorkflowTask, Boolean> {

    /**
     * Should dispatch the Workflow Task to the Workflow Worker. Shouldn't block the thread.
     *
     * @param workflowTask WorkflowTask to be dispatched
     * @return true if the dispatch was successful and false otherwise
     * @throws IllegalArgumentException if {@code workflowTask} doesn't belong to the task queue of the Worker that provided the {@link WorkflowTaskDispatchHandle
     */
    @Override
    Boolean apply(WorkflowTask workflowTask) throws IllegalArgumentException;
  }
}
