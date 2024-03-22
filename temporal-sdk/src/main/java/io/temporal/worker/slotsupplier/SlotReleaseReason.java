package io.temporal.worker.slotsupplier;

public abstract class SlotReleaseReason {

  public static SlotReleaseReason taskComplete() {
    return new TaskComplete();
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

  public Exception getException() {
    return null;
  }

  public static class TaskComplete extends SlotReleaseReason {}

  public static class NeverUsed extends SlotReleaseReason {}

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
