/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.temporal.common.MethodRetry;
import io.temporal.common.RetryOptions;
import io.temporal.internal.common.MergedActivityOptions;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.workflow.shared.TestActivities.TestActivity;
import io.temporal.workflow.shared.TestActivities.TestActivityImpl;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.*;
import org.junit.rules.Timeout;

public class ActivityOptionsTest {
  public @Rule Timeout timeout = Timeout.seconds(10);

  private TestActivityEnvironment testEnv;
  private ActivityOptions defaultOps = ActivityTestOptions.newActivityOptions1();
  private final ActivityOptions methodOps1 = ActivityTestOptions.newActivityOptions2();

  @Before
  public void setUp() {
    testEnv = TestActivityEnvironment.newInstance();
  }

  @After
  public void tearDown() throws Exception {
    testEnv.close();
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
  public void testActivityOptionsMerge() {
    // Assert no changes if no per method options
    ActivityOptions merged =
        ActivityOptions.newBuilder(defaultOps).mergeActivityOptions(null).build();
    Assert.assertEquals(defaultOps, merged);
    // Assert options were overridden with method options
    merged = ActivityOptions.newBuilder(defaultOps).mergeActivityOptions(methodOps1).build();
    Assert.assertEquals(methodOps1, merged);
  }

  @Test
  public void testMergedActivityOptions() {
    MergedActivityOptions options0 = new MergedActivityOptions(null, null, null);

    ActivityOptions a1 =
        ActivityOptions.newBuilder()
            .setScheduleToStartTimeout(Duration.ofMillis(10))
            .setScheduleToCloseTimeout(Duration.ofDays(10))
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(10).build())
            .setCancellationType(ActivityCancellationType.TRY_CANCEL)
            .build();

    Map<String, ActivityOptions> map1 = new HashMap<>();
    map1.put(
        "Activity1",
        ActivityOptions.newBuilder()
            .setScheduleToStartTimeout(Duration.ofMillis(11))
            .setScheduleToCloseTimeout(Duration.ofDays(11))
            .setStartToCloseTimeout(Duration.ofSeconds(11))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(11).build())
            .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
            .build());
    map1.put(
        "Activity2",
        ActivityOptions.newBuilder()
            .setScheduleToStartTimeout(Duration.ofMillis(12))
            .setStartToCloseTimeout(Duration.ofSeconds(12))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(12).build())
            .setCancellationType(ActivityCancellationType.ABANDON)
            .build());

    MergedActivityOptions options1 = new MergedActivityOptions(options0, a1, map1);

    ActivityOptions a2 =
        ActivityOptions.newBuilder()
            .setScheduleToStartTimeout(Duration.ofMillis(20))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(20).build())
            .setCancellationType(ActivityCancellationType.TRY_CANCEL)
            .build();

    MergedActivityOptions options2 = new MergedActivityOptions(options1, a2, null);

    Map<String, ActivityOptions> map3 = new HashMap<>();
    map3.put(
        "Activity1",
        ActivityOptions.newBuilder()
            .setScheduleToStartTimeout(Duration.ofMillis(31))
            .setScheduleToCloseTimeout(Duration.ofDays(31))
            .setStartToCloseTimeout(Duration.ofSeconds(31))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(31).build())
            .build());

    MergedActivityOptions options3 = new MergedActivityOptions(options2, null, map3);

    // From a1
    Assert.assertEquals(
        Duration.ofDays(10), options3.getMergedOptions("Activity2").getScheduleToCloseTimeout());
    // From map1
    Assert.assertEquals(
        Duration.ofSeconds(12), options3.getMergedOptions("Activity2").getStartToCloseTimeout());
    // From a2
    assertEquals(
        ActivityCancellationType.TRY_CANCEL,
        options3.getMergedOptions("Activity2").getCancellationType());
    // From map3
    assertEquals(
        Duration.ofMillis(31), options3.getMergedOptions("Activity1").getScheduleToStartTimeout());
  }

  @Test
  public void testActivityOptionsDefaultInstance() {
    testEnv.registerActivitiesImplementations(new TestActivityImpl());
    TestActivity activity =
        testEnv.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofDays(1)).build());

    // Check that options were set correctly
    Map<String, Duration> optionsValues = activity.activity1();
    Assert.assertEquals(Duration.ofDays(1), optionsValues.get("ScheduleToCloseTimeout"));
  }

  @Test
  public void testOnlyAnnotationsPresent() throws NoSuchMethodException {
    Method method = ActivityOptionsTest.class.getMethod("activityAndRetryOptions");
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
