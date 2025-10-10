package io.temporal.worker.tuning;

import javax.annotation.Nullable;

public abstract class SlotReleaseReason {
  SlotReleaseReason() {}

  public static SlotReleaseReason taskComplete() {
    return new TaskComplete();
  }

  public static SlotReleaseReason willRetry() {
    return new WillRetry();
  }

  public static SlotReleaseReason neverUsed() {
    return new NeverUsed();
  }

  public static SlotReleaseReason error(Exception exception) {
    return new Error(exception);
  }

  public boolean isError() {
    return false;
  }

  /**
   * @return the exception that caused the slot to be released, if this is a reason of type {@link
   *     Error}.
   */
  public @Nullable Exception getException() {
    return null;
  }

  /** The slot was released because the task was completed (regardless of status). */
  public static class TaskComplete extends SlotReleaseReason {}

  /** The slot was released because the task will be retried. */
  public static class WillRetry extends SlotReleaseReason {}

  /** The slot was released because it was never needed. */
  public static class NeverUsed extends SlotReleaseReason {}

  /**
   * The slot was released because some error was encountered before the slot could be used to
   * actually process the task.
   */
  public static class Error extends SlotReleaseReason {
    private final Exception exception;

    private Error(Exception exception) {
      this.exception = exception;
    }

    @Override
    public boolean isError() {
      return true;
    }

    @Override
    public Exception getException() {
      return exception;
    }
  }
}
