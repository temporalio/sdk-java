package com.uber.cadence.internal.worker;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

class ExecutorThreadFactory implements ThreadFactory {

    private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    private AtomicInteger threadIndex = new AtomicInteger();

    private final String threadPrefix;

    ExecutorThreadFactory(String threadPrefix, Thread.UncaughtExceptionHandler eh) {
        this.threadPrefix = threadPrefix;
        this.uncaughtExceptionHandler = eh;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread result = new Thread(r);
        result.setName(threadPrefix + ": " + (threadIndex.incrementAndGet()));
        result.setUncaughtExceptionHandler(uncaughtExceptionHandler);
        return result;
    }
}
