package io.temporal.internal.worker;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

class BlockCallerPolicy implements RejectedExecutionHandler {

  @Override
  public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
    // Without this check the call hangs forever on the queue put.
    if (executor.isShutdown()) {
      throw new RejectedExecutionException("Executor is shutdown");
    }
    try {
      // block until there's room
      executor.getQueue().put(r);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RejectedExecutionException("Unexpected InterruptedException", e);
    }
  }
}
