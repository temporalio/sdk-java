package io.temporal.internal.worker;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class ExecutorThreadFactory implements ThreadFactory {
  private final String threadPrefix;

  private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
  private final ClassLoader contextClassLoader;
  private final AtomicInteger threadIndex = new AtomicInteger();

  public ExecutorThreadFactory(String threadPrefix, Thread.UncaughtExceptionHandler eh) {
    this.threadPrefix = threadPrefix;
    this.uncaughtExceptionHandler = eh;
    this.contextClassLoader = Thread.currentThread().getContextClassLoader();
  }

  @Override
  public Thread newThread(Runnable r) {
    Thread result = new Thread(r);
    result.setName(threadPrefix + ": " + threadIndex.incrementAndGet());
    result.setUncaughtExceptionHandler(uncaughtExceptionHandler);
    if (contextClassLoader != null) {
      result.setContextClassLoader(contextClassLoader);
    }
    return result;
  }
}
