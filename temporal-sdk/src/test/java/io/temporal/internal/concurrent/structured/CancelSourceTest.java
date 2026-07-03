package io.temporal.internal.concurrent.structured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.common.CancellationToken;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CancelSourceTest {

  @Test
  public void cancelRunsAllCallbacksEvenIfOneThrows() {
    CancelSource source = new CancelSource();
    AtomicInteger callbacksRan = new AtomicInteger();

    source
        .token()
        .onCancel(
            () -> {
              callbacksRan.incrementAndGet();
              throw new IllegalStateException("boom");
            });
    source.token().onCancel(callbacksRan::incrementAndGet);

    source.cancel();
    source.cancel();

    assertEquals(2, callbacksRan.get());
  }

  @Test
  public void closingRegistrationPreventsCallback() {
    CancelSource source = new CancelSource();
    AtomicInteger callbacksRan = new AtomicInteger();

    CancellationToken.Registration registration =
        source.token().onCancel(callbacksRan::incrementAndGet);
    registration.close();

    source.cancel();

    assertEquals(0, callbacksRan.get());
  }

  @Test
  public void onCancelRunsImmediatelyWhenAlreadyCancelled() {
    CancelSource source = new CancelSource();
    AtomicInteger callbacksRan = new AtomicInteger();

    source.cancel();
    source.token().onCancel(callbacksRan::incrementAndGet);

    assertEquals(1, callbacksRan.get());
  }

  @Test
  public void linkedCancellationFlowsDownstreamOnly() {
    CancelSource parent = new CancelSource();
    CancelSource child = CancelSource.linkedTo(parent.token());

    child.cancel();

    assertTrue(child.isCancelled());
    assertFalse(parent.isCancelled());

    parent.cancel();

    assertTrue(child.isCancelled());
    assertTrue(parent.isCancelled());
  }

  @Test
  public void linkedToCancelsWhenAnyParentCancels() {
    CancelSource a = new CancelSource();
    CancelSource b = new CancelSource();
    CancelSource child = CancelSource.linkedTo(a.token(), b.token());

    assertFalse(child.isCancelled());

    b.cancel();

    assertTrue(child.isCancelled());
    assertFalse(a.isCancelled());
  }

  @Test
  public void linkedToAlreadyCancelledParentCancelsImmediately() {
    CancelSource parent = new CancelSource();
    parent.cancel();

    CancelSource child = CancelSource.linkedTo(parent.token());

    assertTrue(child.isCancelled());
  }

  @Test
  public void registrationCloseAfterCancelIsSafe() {
    CancelSource source = new CancelSource();
    CancellationToken.Registration registration =
        source.token().onCancel(() -> {}); // registered before cancel

    source.cancel();

    registration.close(); // must not throw even though cancel() dropped the callback list
  }
}
