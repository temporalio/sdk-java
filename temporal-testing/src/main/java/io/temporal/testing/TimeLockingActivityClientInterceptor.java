package io.temporal.testing;

import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.common.interceptors.ActivityClientCallsInterceptorBase;
import io.temporal.common.interceptors.ActivityClientInterceptorBase;
import io.temporal.serviceclient.TestServiceStubs;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class TimeLockingActivityClientInterceptor extends ActivityClientInterceptorBase {

  private final IdempotentTimeLocker locker;

  TimeLockingActivityClientInterceptor(TestServiceStubs testServiceStubs) {
    this.locker = new IdempotentTimeLocker(testServiceStubs);
  }

  @Override
  public ActivityClientCallsInterceptor activityClientCallsInterceptor(
      ActivityClientCallsInterceptor next) {
    return new TimeLockingActivityClientCallsInterceptor(next);
  }

  private final class TimeLockingActivityClientCallsInterceptor
      extends ActivityClientCallsInterceptorBase {

    private TimeLockingActivityClientCallsInterceptor(ActivityClientCallsInterceptor next) {
      super(next);
    }

    @Override
    public <R> ActivityClientCallsInterceptor.GetActivityResultOutput<R> getActivityResult(
        ActivityClientCallsInterceptor.GetActivityResultInput<R> input) throws TimeoutException {
      locker.unlockTimeSkipping();
      try {
        return super.getActivityResult(input);
      } finally {
        locker.lockTimeSkipping();
      }
    }

    @Override
    public <R>
        CompletableFuture<ActivityClientCallsInterceptor.GetActivityResultOutput<R>>
            getActivityResultAsync(ActivityClientCallsInterceptor.GetActivityResultInput<R> input) {
      return new TimeLockingFuture<>(super.getActivityResultAsync(input));
    }
  }

  private final class TimeLockingFuture<R> extends CompletableFuture<R> {

    private TimeLockingFuture(CompletableFuture<R> resultAsync) {
      @SuppressWarnings({"FutureReturnValueIgnored", "unused"})
      CompletableFuture<R> ignored =
          resultAsync.whenComplete(
              (r, e) -> {
                if (e == null) {
                  complete(r);
                } else {
                  completeExceptionally(e);
                }
              });
    }

    @Override
    public R get() throws InterruptedException, ExecutionException {
      locker.unlockTimeSkipping();
      try {
        return super.get();
      } finally {
        locker.lockTimeSkipping();
      }
    }

    @Override
    public R get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      locker.unlockTimeSkipping();
      try {
        return super.get(timeout, unit);
      } finally {
        locker.lockTimeSkipping();
      }
    }

    @Override
    public R join() {
      locker.unlockTimeSkipping();
      try {
        return super.join();
      } finally {
        locker.lockTimeSkipping();
      }
    }
  }
}
