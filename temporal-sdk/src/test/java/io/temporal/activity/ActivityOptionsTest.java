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

package io.temporal.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.workflow.shared.TestActivities.TestActivity;
import io.temporal.workflow.shared.TestActivities.TestActivityImpl;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ActivityOptionsTest {

  private TestActivityEnvironment testEnv;

  @Before
  public void setUp() {
    testEnv = TestActivityEnvironment.newInstance();
  }

  @MethodRetry(
      initialIntervalSeconds = 12,
      backoffCoefficient = 1.97,
      maximumAttempts = 234567,
      maximumIntervalSeconds = 22,
      doNotRetry = {"java.lang.NullPointerException", "java.lang.UnsupportedOperationException"})
  public void activityAndRetryOptions() {}

  @Test
  public void testActivityOptions() {
    testEnv.registerActivitiesImplementations(new TestActivityImpl());
    try {
      testEnv.newActivityStub(TestActivity.class);
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e instanceof IllegalArgumentException);
      Assert.assertEquals(
          e.getMessage(),
          "Both StartToCloseTimeout and ScheduleToCloseTimeout aren't specified for Activity1 activity. Please set at least one of the above through the ActivityStub or WorkflowImplementationOptions.");
    }
  }

  @Test
  public void testActivityOptionsDefaultInstance() {
    testEnv.registerActivitiesImplementations(new TestActivityImpl());
    TestActivity activity =
        testEnv.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofDays(1)).build(),
            null);

    // Check that options were set correctly
    Map<String, Duration> optionsValues = activity.activity1();
    Assert.assertEquals(Duration.ofDays(1), optionsValues.get("ScheduleToCloseTimeout"));
  }

  @Test
  public void testOnlyAnnotationsPresent() throws NoSuchMethodException {
    Method method = ActivityOptionsTest.class.getMethod("activityAndRetryOptions");
    ActivityMethod a = method.getAnnotation(ActivityMethod.class);
    MethodRetry r = method.getAnnotation(MethodRetry.class);
    ActivityOptions o = ActivityOptions.newBuilder().build();
    ActivityOptions merged = ActivityOptions.newBuilder(o).mergeMethodRetry(r).build();

    RetryOptions rMerged = merged.getRetryOptions();
    assertEquals(r.maximumAttempts(), rMerged.getMaximumAttempts());
    assertEquals(r.backoffCoefficient(), rMerged.getBackoffCoefficient(), 0.0);
    assertEquals(Duration.ofSeconds(r.initialIntervalSeconds()), rMerged.getInitialInterval());
    assertEquals(Duration.ofSeconds(r.maximumIntervalSeconds()), rMerged.getMaximumInterval());
    Assert.assertArrayEquals(r.doNotRetry(), rMerged.getDoNotRetry());
  }

  @Test
  public void testLocalActivityOptions() {
    try {
      LocalActivityOptions.newBuilder().validateAndBuildWithDefaults();
      fail("unreachable");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("scheduleToCloseTimeout"));
    }
    assertEquals(
        Duration.ofSeconds(10),
        LocalActivityOptions.newBuilder()
            .setScheduleToCloseTimeout(Duration.ofSeconds(10))
            .validateAndBuildWithDefaults()
            .getScheduleToCloseTimeout());

    assertEquals(
        Duration.ofSeconds(11),
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(11))
            .validateAndBuildWithDefaults()
            .getStartToCloseTimeout());

    RetryOptions retryOptions =
        LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(11))
            .validateAndBuildWithDefaults()
            .getRetryOptions();
    assertNotNull(retryOptions);
    assertEquals(2.0, retryOptions.getBackoffCoefficient(), 0e-5);
    assertEquals(Duration.ofSeconds(1), retryOptions.getInitialInterval());
    assertEquals(null, retryOptions.getMaximumInterval());
  }
}
