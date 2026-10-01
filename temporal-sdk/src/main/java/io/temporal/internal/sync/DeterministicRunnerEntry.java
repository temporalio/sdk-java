package io.temporal.internal.sync;

import java.util.concurrent.Future;

/** A task ordered and run by the deterministic workflow runner. */
interface DeterministicRunnerEntry {
  int getPriority();

  boolean runUntilBlocked(long deadlockDetectionTimeoutMs);

  boolean isDone();

  Throwable getUnhandledException();

  Future<?> stopNow();

  void addStackTrace(StringBuilder result);
}
