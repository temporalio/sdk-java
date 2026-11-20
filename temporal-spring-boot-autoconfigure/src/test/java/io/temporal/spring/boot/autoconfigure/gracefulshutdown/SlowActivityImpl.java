package io.temporal.spring.boot.autoconfigure.gracefulshutdown;

import io.temporal.spring.boot.ActivityImpl;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ActivityImpl(taskQueues = SlowWorkflow.TASK_QUEUE)
public class SlowActivityImpl implements SlowActivity {

  private static final long DURATION_MILLIS = 2000;

  private final CountDownLatch started = new CountDownLatch(1);
  private final AtomicBoolean completed = new AtomicBoolean();

  @Override
  public void run() {
    started.countDown();
    try {
      Thread.sleep(DURATION_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    completed.set(true);
  }

  public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
    return started.await(timeout, unit);
  }

  public boolean isCompleted() {
    return completed.get();
  }
}
