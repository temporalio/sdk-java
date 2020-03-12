/*
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

import static org.junit.Assert.*;

import io.grpc.Status;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityMethod;
import io.temporal.client.ActivityCancelledException;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.workflow.ActivityFailureException;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

public class ActivityTestingTest {

  private TestActivityEnvironment testEnvironment;

  @Before
  public void setUp() {
    testEnvironment = TestActivityEnvironment.newInstance();
  }

  public interface TestActivity {

    String activity1(String input);
  }

  private static class ActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      return Activity.getTask().getActivityType() + "-" + input;
    }
  }

  @Test
  public void testSuccess() {
    testEnvironment.registerActivitiesImplementations(new ActivityImpl());
    TestActivity activity = testEnvironment.newActivityStub(TestActivity.class);
    String result = activity.activity1("input1");
    assertEquals("TestActivity::activity1-input1", result);
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
    } catch (ActivityFailureException e) {
      assertTrue(e.getMessage().contains("TestActivity::activity1"));
      assertTrue(e.getCause() instanceof IOException);
      assertEquals("simulated", e.getCause().getMessage());
    }
  }

  private static class HeartbeatActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      Activity.heartbeat("details1");
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

  public interface InterruptibleTestActivity {

    @ActivityMethod(scheduleToCloseTimeoutSeconds = 1000, heartbeatTimeoutSeconds = 1)
    void activity1() throws InterruptedException;
  }

  private static class BurstHeartbeatActivityImpl implements InterruptibleTestActivity {

    @Override
    public void activity1() throws InterruptedException {
      for (int i = 0; i < 10; i++) {
        Activity.heartbeat(i);
      }
      Thread.sleep(1000);
      for (int i = 10; i < 20; i++) {
        Activity.heartbeat(i);
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
      for (int i = 0; i < 10; i++) {
        Activity.heartbeat(null);
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
        Activity.heartbeat(null);
        fail("unreachable");
      } catch (ActivityCancelledException e) {
        System.out.println("activity cancelled");
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
      Activity.heartbeat(null);
      Thread.sleep(100);
      Activity.heartbeat(null);
      Thread.sleep(1000);
      try {
        Activity.heartbeat(null);
        fail("unreachable");
      } catch (ActivityCancelledException e) {
        System.out.println("activity cancelled");
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
      Activity.heartbeat(null);
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
}
