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

package com.uber.cadence.internal.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.uber.cadence.activity.Activity;
import com.uber.cadence.testing.TestActivityEnvironment;
import com.uber.cadence.testing.TestEnvironment;
import com.uber.cadence.workflow.ActivityFailureException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.BeforeClass;
import org.junit.Test;

public class ActivityTestingTest {

  private static TestEnvironment testEnvironment;

  @BeforeClass
  public static void setUp() {
    testEnvironment = TestEnvironment.newInstance();
  }

  public interface TestActivity {

    String activity1(String input);
  }

  private static class ActivityImpl implements TestActivity {

    @Override
    public String activity1(String input) {
      return Activity.getTask().getActivityType().getName() + "-" + input;
    }
  }

  @Test
  public void testSuccess() {
    TestActivityEnvironment env = testEnvironment.activityEnvironment();
    env.registerActivitiesImplementations(new ActivityImpl());
    TestActivity activity = env.newActivityStub(TestActivity.class);
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
    TestActivityEnvironment env = testEnvironment.activityEnvironment();
    env.registerActivitiesImplementations(new AngryActivityImpl());
    TestActivity activity = env.newActivityStub(TestActivity.class);
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
    TestActivityEnvironment env = testEnvironment.activityEnvironment();
    env.registerActivitiesImplementations(new HeartbeatActivityImpl());
    AtomicReference<String> details = new AtomicReference<>();
    env.setActivityHeartbeatListener(
        String.class,
        (d) -> {
          details.set(d);
        });
    TestActivity activity = env.newActivityStub(TestActivity.class);
    String result = activity.activity1("input1");
    assertEquals("input1", result);
    assertEquals("details1", details.get());
  }
}
