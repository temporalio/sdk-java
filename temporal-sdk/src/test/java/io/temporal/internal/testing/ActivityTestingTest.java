/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.Status;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.ActivityInterface;
import io.temporal.client.ActivityCanceledException;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class ActivityTestingTest {

  private TestActivityEnvironment testEnvironment;

  public @Rule Timeout timeout = Timeout.seconds(10);

  @Before
  public void setUp() {
    testEnvironment = TestActivityEnvironment.newInstance();
  }

  @ActivityInterface
  public interface TestActivity {

    String activity1(String input);
  }

  private static class ActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      ActivityExecutionContext executionContext = Activity.getExecutionContext();
      return executionContext.getInfo().getActivityType() + "-" + input;
    }
  }

  @Test
  public void testSuccess() {
    testEnvironment.registerActivitiesImplementations(new ActivityImpl());
    TestActivity activity = testEnvironment.newActivityStub(TestActivity.class);
    String result = activity.activity1("input1");
    assertEquals("Activity1-input1", result);
  }

  private static class AngryActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      throw Activity.wrap(new IOException("simulated"));
    }
  }

  @Test
  public void testFailure() {
    testEnvironment.registerActivitiesImplementations(new AngryActivityImpl());
    TestActivity activity = testEnvironment.newActivityStub(TestActivity.class);
    try {
      activity.activity1("input1");
      fail("unreachable");
    } catch (ActivityFailure e) {
      assertTrue(e.getMessage().contains("Activity1"));
      assertTrue(e.getCause() instanceof ApplicationFailure);
      assertTrue(((ApplicationFailure) e.getCause()).getType().equals(IOException.class.getName()));

      assertEquals(
          "message='simulated', type='java.io.IOException', nonRetryable=false",
          e.getCause().getMessage());
      e.printStackTrace();
    }
  }

  private static class HeartbeatActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      Activity.getExecutionContext().heartbeat("details1");
      return input;
    }
  }

  @Test
  public void testHeartbeat() {
    testEnvironment.registerActivitiesImplementations(new HeartbeatActivityImpl());
    AtomicReference<String> details = new AtomicReference<>();
    testEnvironment.setActivityHeartbeatListener(String.class, details::set);
    TestActivity activity = testEnvironment.newActivityStub(TestActivity.class);
    String result = activity.activity1("input1");
    assertEquals("input1", result);
    assertEquals("details1", details.get());
  }

  @ActivityInterface
  public interface InterruptibleTestActivity {
    void activity1() throws InterruptedException;
  }

  private static class BurstHeartbeatActivityImpl implements InterruptibleTestActivity {

    @Override
    public void activity1() throws InterruptedException {
      ActivityExecutionContext ctx = Activity.getExecutionContext();
      for (int i = 0; i < 10; i++) {
        ctx.heartbeat(i);
      }
      Thread.sleep(1000);
      for (int i = 10; i < 20; i++) {
        ctx.heartbeat(i);
      }
    }
  }

  @Test
  public void testHeartbeatThrottling() throws InterruptedException {
    testEnvironment.registerActivitiesImplementations(new BurstHeartbeatActivityImpl());
    Set<Integer> details = ConcurrentHashMap.newKeySet();
    testEnvironment.setActivityHeartbeatListener(Integer.class, details::add);
    InterruptibleTestActivity activity =
        testEnvironment.newActivityStub(InterruptibleTestActivity.class);
    activity.activity1();
    assertEquals(2, details.size());
  }

  private static class BurstHeartbeatActivity2Impl implements InterruptibleTestActivity {

    @Override
    public void activity1() throws InterruptedException {
      ActivityExecutionContext ctx = Activity.getExecutionContext();
      for (int i = 0; i < 10; i++) {
        ctx.heartbeat(null);
      }
      Thread.sleep(1200);
    }
  }

  // This test covers the logic where another heartbeat request is sent by the background thread,
  // after wait period expires.
  @Test
  public void testHeartbeatThrottling2() throws InterruptedException {
    testEnvironment.registerActivitiesImplementations(new BurstHeartbeatActivity2Impl());
    AtomicInteger count = new AtomicInteger();
    testEnvironment.setActivityHeartbeatListener(Void.class, i -> count.incrementAndGet());
    InterruptibleTestActivity activity =
        testEnvironment.newActivityStub(InterruptibleTestActivity.class);
    activity.activity1();
    assertEquals(2, count.get());
  }

  private static class HeartbeatCancellationActivityImpl implements InterruptibleTestActivity {

    @Override
    public void activity1() throws InterruptedException {
      try {
        Activity.getExecutionContext().heartbeat(null);
        fail("unreachable");
      } catch (ActivityCanceledException e) {
      }
    }
  }

  @Test
  public void testHeartbeatCancellation() throws InterruptedException {
    testEnvironment.registerActivitiesImplementations(new HeartbeatCancellationActivityImpl());
    testEnvironment.requestCancelActivity();
    InterruptibleTestActivity activity =
        testEnvironment.newActivityStub(InterruptibleTestActivity.class);
    activity.activity1();
  }

  private static class CancellationOnNextHeartbeatActivityImpl
      implements InterruptibleTestActivity {

    @Override
    public void activity1() throws InterruptedException {
      ActivityExecutionContext ctx = Activity.getExecutionContext();
      ctx.heartbeat(null);
      Thread.sleep(100);
      ctx.heartbeat(null);
      Thread.sleep(1000);
      try {
        ctx.heartbeat(null);
        fail("unreachable");
      } catch (ActivityCanceledException e) {
        // expected
      }
    }
  }

  @Test
  public void testCancellationOnNextHeartbeat() throws InterruptedException {
    testEnvironment.registerActivitiesImplementations(
        new CancellationOnNextHeartbeatActivityImpl());
    // Request cancellation after the second heartbeat
    AtomicInteger count = new AtomicInteger();
    testEnvironment.setActivityHeartbeatListener(
        Void.class,
        (d) -> {
          if (count.incrementAndGet() == 2) {
            testEnvironment.requestCancelActivity();
          }
        });
    InterruptibleTestActivity activity =
        testEnvironment.newActivityStub(InterruptibleTestActivity.class);
    activity.activity1();
  }

  private static class SimpleHeartbeatActivityImpl implements InterruptibleTestActivity {

    @Override
    public void activity1() throws InterruptedException {
      Activity.getExecutionContext().heartbeat(null);
      // Make sure that the activity lasts longer than the retry period.
      Thread.sleep(3000);
    }
  }

  @Test
  public void testHeartbeatIntermittentError() throws InterruptedException {
    testEnvironment.registerActivitiesImplementations(new SimpleHeartbeatActivityImpl());
    AtomicInteger count = new AtomicInteger();
    testEnvironment.setActivityHeartbeatListener(
        Integer.class,
        i -> {
          int newCount = count.incrementAndGet();
          if (newCount < 3) {
            throw Status.INTERNAL
                .withDescription("Simulated intermittent failure")
                .asRuntimeException();
          }
        });
    InterruptibleTestActivity activity =
        testEnvironment.newActivityStub(InterruptibleTestActivity.class);
    activity.activity1();
    assertEquals(3, count.get());
  }

  public interface A {
    void a();
  }

  @ActivityInterface
  public interface B extends A {
    void b();
  }

  @ActivityInterface
  public interface C extends B, A {
    void c();
  }

  @ActivityInterface(namePrefix = "D_")
  public interface D extends A {
    void d();
  }

  @ActivityInterface
  public interface E extends D {
    void e();
  }

  public class CImpl implements C {
    private final List<String> invocations = new ArrayList<>();

    @Override
    public void a() {
      invocations.add("a");
    }

    @Override
    public void b() {
      invocations.add("b");
    }

    @Override
    public void c() {
      invocations.add("c");
    }
  }

  public class BImpl implements B {
    private final List<String> invocations = new ArrayList<>();

    @Override
    public void a() {
      invocations.add("a");
    }

    @Override
    public void b() {
      invocations.add("b");
    }
  }

  public class DImpl implements D {
    private final List<String> invocations = new ArrayList<>();

    @Override
    public void a() {
      invocations.add("a");
    }

    @Override
    public void d() {
      invocations.add("d");
    }
  }

  public class EImpl implements E {
    private final List<String> invocations = new ArrayList<>();

    @Override
    public void a() {
      invocations.add("a");
    }

    @Override
    public void d() {
      invocations.add("d");
    }

    @Override
    public void e() {
      invocations.add("e");
    }
  }

  public abstract class BaseE implements E {
    protected final List<String> invocations = new ArrayList<>();

    @Override
    public void a() {
      doA();
    }

    @Override
    public void d() {
      invocations.add("d");
    }

    @Override
    public void e() {
      invocations.add("e");
    }

    abstract void doA();
  }

  public class EImplBaseExtended extends BaseE {
    @Override
    void doA() {
      invocations.add("a");
    }
  }

  @Test
  public void testInvokingActivityByBaseInterface1() {
    BImpl bImpl = new BImpl();
    DImpl dImpl = new DImpl();
    testEnvironment.registerActivitiesImplementations(bImpl, dImpl);
    try {
      testEnvironment.newActivityStub(A.class);
      fail("A doesn't implement activity");
    } catch (IllegalArgumentException e) {
      // expected as A doesn't implement any activity
    }
    B b = testEnvironment.newActivityStub(B.class);
    b.a();
    b.b();
    A a = b;
    a.a();
    D d = testEnvironment.newActivityStub(D.class);
    d.a();
    d.d();
    a = d;
    a.a();
    List<String> expectedB = new ArrayList<>();
    expectedB.add("a");
    expectedB.add("b");
    expectedB.add("a");
    assertEquals(expectedB, bImpl.invocations);

    List<String> expectedD = new ArrayList<>();
    expectedD.add("a");
    expectedD.add("d");
    expectedD.add("a");
    assertEquals(expectedD, dImpl.invocations);
  }

  @Test
  public void testInvokingActivityByBaseInterface1CallRegisterTwice() {
    BImpl bImpl = new BImpl();
    DImpl dImpl = new DImpl();
    testEnvironment.registerActivitiesImplementations(bImpl);
    testEnvironment.registerActivitiesImplementations(dImpl);
    try {
      testEnvironment.newActivityStub(A.class);
      fail("A doesn't implement activity");
    } catch (IllegalArgumentException e) {
      // expected as A doesn't implement any activity
    }
    B b = testEnvironment.newActivityStub(B.class);
    b.a();
    b.b();
    A a = b;
    a.a();
    D d = testEnvironment.newActivityStub(D.class);
    d.a();
    d.d();
    a = d;
    a.a();
    List<String> expectedB = new ArrayList<>();
    expectedB.add("a");
    expectedB.add("b");
    expectedB.add("a");
    assertEquals(expectedB, bImpl.invocations);

    List<String> expectedD = new ArrayList<>();
    expectedD.add("a");
    expectedD.add("d");
    expectedD.add("a");
    assertEquals(expectedD, dImpl.invocations);
  }

  @Test
  public void testInvokingActivityByBaseInterface2() {
    EImpl eImpl = new EImpl();
    testEnvironment.registerActivitiesImplementations(eImpl);
    E e = testEnvironment.newActivityStub(E.class);
    e.a();
    e.d();
    e.e();
    D d = testEnvironment.newActivityStub(D.class);
    d.a();
    d.d();
    List<String> expectedE = new ArrayList<>();
    expectedE.add("a");
    expectedE.add("d");
    expectedE.add("e");
    expectedE.add("a");
    expectedE.add("d");
    assertEquals(expectedE, eImpl.invocations);
  }

  @Test
  public void testInvokingInheritedActivityByBaseInterfaces() {
    EImplBaseExtended eImpl = new EImplBaseExtended();
    testEnvironment.registerActivitiesImplementations(eImpl);
    E e = testEnvironment.newActivityStub(E.class);
    e.a();
    e.d();
    e.e();
    D d = testEnvironment.newActivityStub(D.class);
    d.a();
    d.d();
    List<String> expectedE = new ArrayList<>();
    expectedE.add("a");
    expectedE.add("d");
    expectedE.add("e");
    expectedE.add("a");
    expectedE.add("d");
    assertEquals(expectedE, eImpl.invocations);
  }

  @Test
  public void testDuplicates1() {
    try {
      CImpl cImpl = new CImpl();
      testEnvironment.registerActivitiesImplementations(cImpl);
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("A.a()"));
      assertTrue(e.getMessage().contains("Duplicated"));
    }
  }

  @Test
  public void testDuplicates2() {
    DImpl dImpl = new DImpl();
    EImpl eImpl = new EImpl();
    try {
      testEnvironment.registerActivitiesImplementations(dImpl, eImpl);
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("a"));
      assertTrue(e.getMessage().contains("already registered"));
    }
  }

  @Test
  public void testDuplicates2CallRegisterTwice() {
    DImpl dImpl = new DImpl();
    EImpl eImpl = new EImpl();
    try {
      testEnvironment.registerActivitiesImplementations(dImpl);
      testEnvironment.registerActivitiesImplementations(eImpl);
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("a"));
      assertTrue(e.getMessage().contains("already registered"));
    }
  }
}
