package io.temporal.internal.activity;

import static org.junit.Assert.*;

import io.temporal.activity.ActivityExecutionContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;

public class CurrentActivityExecutionContextTest {

  private static ActivityExecutionContext proxyContext() {
    InvocationHandler handler = (proxy, method, args) -> null;
    return (ActivityExecutionContext)
        Proxy.newProxyInstance(
            ActivityExecutionContext.class.getClassLoader(),
            new Class[] {ActivityExecutionContext.class},
            handler);
  }

  @Test
  public void platformThreadNestedSetUnsetBehavior() {
    ActivityExecutionContext ctx1 = proxyContext();
    ActivityExecutionContext ctx2 = proxyContext();

    assertFalse(CurrentActivityExecutionContext.isSet());
    assertThrows(IllegalStateException.class, CurrentActivityExecutionContext::get);

    CurrentActivityExecutionContext.set(ctx1);
    assertTrue(CurrentActivityExecutionContext.isSet());
    assertSame("should return ctx1", ctx1, CurrentActivityExecutionContext.get());

    CurrentActivityExecutionContext.set(ctx2);
    assertTrue(CurrentActivityExecutionContext.isSet());
    assertSame("should return ctx2 (top of stack)", ctx2, CurrentActivityExecutionContext.get());

    CurrentActivityExecutionContext.unset();
    assertTrue(CurrentActivityExecutionContext.isSet());
    assertSame("after popping, should return ctx1", ctx1, CurrentActivityExecutionContext.get());

    CurrentActivityExecutionContext.unset();
    assertFalse(CurrentActivityExecutionContext.isSet());
    assertThrows(
        "get() should throw after final unset",
        IllegalStateException.class,
        CurrentActivityExecutionContext::get);
  }

  @Test
  public void virtualThreadNestedSetUnsetBehavior_ifSupported() throws Exception {
    boolean supportsVirtual;
    try {
      Thread.class.getMethod("startVirtualThread", Runnable.class);
      supportsVirtual = true;
    } catch (NoSuchMethodException e) {
      supportsVirtual = false;
    }

    Assume.assumeTrue("Virtual threads not supported in this JVM; skipping", supportsVirtual);

    AtomicReference<Throwable> failure = new AtomicReference<>(null);
    AtomicReference<ActivityExecutionContext> seenAfterFirstSet = new AtomicReference<>(null);
    AtomicReference<ActivityExecutionContext> seenAfterSecondSet = new AtomicReference<>(null);
    AtomicReference<Boolean> seenIsSetAfterFinalUnset = new AtomicReference<>(null);

    Thread vt =
        Thread.startVirtualThread(
            () -> {
              try {
                ActivityExecutionContext vctx1 = proxyContext();
                ActivityExecutionContext vctx2 = proxyContext();

                assertFalse(CurrentActivityExecutionContext.isSet());
                try {
                  CurrentActivityExecutionContext.get();
                  fail("get() should have thrown when no context is set");
                } catch (IllegalStateException expected) {
                }

                CurrentActivityExecutionContext.set(vctx1);
                seenAfterFirstSet.set(CurrentActivityExecutionContext.get());

                CurrentActivityExecutionContext.set(vctx2);
                seenAfterSecondSet.set(CurrentActivityExecutionContext.get());

                CurrentActivityExecutionContext.unset();
                ActivityExecutionContext afterPop = CurrentActivityExecutionContext.get();
                if (afterPop != vctx1) {
                  throw new AssertionError("after pop expected vctx1 but got " + afterPop);
                }

                CurrentActivityExecutionContext.unset();
                seenIsSetAfterFinalUnset.set(CurrentActivityExecutionContext.isSet());
                try {
                  CurrentActivityExecutionContext.get();
                  throw new AssertionError("get() should have thrown after final unset");
                } catch (IllegalStateException expected) {
                }
              } catch (Throwable t) {
                failure.set(t);
              }
            });

    vt.join();

    if (failure.get() != null) {
      Throwable t = failure.get();
      if (t instanceof AssertionError) {
        throw (AssertionError) t;
      } else {
        throw new RuntimeException(t);
      }
    }

    assertNotNull("virtual thread did not record first set", seenAfterFirstSet.get());
    assertNotNull("virtual thread did not record second (nested) set", seenAfterSecondSet.get());
    assertFalse("expected context to be unset at the end", seenIsSetAfterFinalUnset.get());
  }
}
