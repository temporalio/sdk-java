package io.temporal.worker.slotsupplier;

public abstract class SlotReleaseReason {

  static SlotReleaseReason taskComplete() {
    return new TaskComplete();
  }

  static SlotReleaseReason neverUsed() {
    return new NeverUsed();
  }

  static SlotReleaseReason error(Exception exception) {
    return new Error(exception);
  }

  public boolean isError() {
    return false;
  }

  public Exception getException() {
    return null;
  }

  private static class TaskComplete extends SlotReleaseReason {}

  private static class NeverUsed extends SlotReleaseReason {}

  private static class Error extends SlotReleaseReason {
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
