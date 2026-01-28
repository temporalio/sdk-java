package io.temporal.internal.worker;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class ExecutorThreadFactory implements ThreadFactory {
  private final String threadPrefix;

  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
  private final AtomicInteger threadIndex = new AtomicInteger();

  public ExecutorThreadFactory(String threadPrefix, Thread.UncaughtExceptionHandler eh) {
    this.threadPrefix = threadPrefix;
    this.uncaughtExceptionHandler = eh;
  }

  @Override
  public Thread newThread(Runnable r) {
    Thread result = new Thread(r);
    result.setName(threadPrefix + ": " + threadIndex.incrementAndGet());
    result.setUncaughtExceptionHandler(uncaughtExceptionHandler);
    return result;
  }
}
