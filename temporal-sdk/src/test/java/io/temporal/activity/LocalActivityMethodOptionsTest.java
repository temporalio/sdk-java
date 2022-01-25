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

import io.temporal.common.RetryOptions;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.workflow.shared.TestActivities.TestLocalActivity;
import io.temporal.workflow.shared.TestActivities.TestLocalActivityImpl;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class LocalActivityMethodOptionsTest {

  private static final LocalActivityOptions defaultOps =
      LocalActivityOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofDays(1))
          .setStartToCloseTimeout(Duration.ofSeconds(2))
          .setLocalRetryThreshold(Duration.ofSeconds(2))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
          .setDoNotIncludeArgumentsIntoMarker(true)
          .build();

  private static final LocalActivityOptions methodOps1 =
      LocalActivityOptions.newBuilder()
          .setScheduleToCloseTimeout(Duration.ofDays(2))
          .setStartToCloseTimeout(Duration.ofSeconds(3))
          .setLocalRetryThreshold(Duration.ofSeconds(3))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
          .setDoNotIncludeArgumentsIntoMarker(false)
          .build();
  private static final LocalActivityOptions methodOps2 =
      LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(4)).build();
  private static final Map<String, LocalActivityOptions> perMethodOptionsMap =
      new HashMap<String, LocalActivityOptions>() {
        {
          put("LocalActivity1", methodOps2);
        }
      };
  private TestActivityEnvironment testEnv;

  @Before
  public void setUp() {
    testEnv = TestActivityEnvironment.newInstance();
  }

  @Test
  public void testActivityOptionsMerge() {
    // Assert no changes if no per method options
    LocalActivityOptions merged =
        LocalActivityOptions.newBuilder(defaultOps).mergeActivityOptions(null).build();
    Assert.assertEquals(defaultOps, merged);
    // Assert options were overridden with method options
    merged = LocalActivityOptions.newBuilder(defaultOps).mergeActivityOptions(methodOps1).build();
    Assert.assertEquals(methodOps1, merged);
    // Check that if doNotIncludeArgumentsIntoMarker is not set, it defaults to false.
    Assert.assertFalse(methodOps2.isDoNotIncludeArgumentsIntoMarker());
    // Check that original value of doNotIncludeArgumentsIntoMarker is not overridden if it's not
    // set in override.
    merged = LocalActivityOptions.newBuilder(defaultOps).mergeActivityOptions(methodOps2).build();
    Assert.assertEquals(
        defaultOps.isDoNotIncludeArgumentsIntoMarker(), merged.isDoNotIncludeArgumentsIntoMarker());
  }

  @Test
  public void testLocalActivityMethodOptions() {
    testEnv.registerActivitiesImplementations(new TestLocalActivityImpl());
    TestLocalActivity localActivity =
        testEnv.newLocalActivityStub(TestLocalActivity.class, defaultOps, perMethodOptionsMap);

    // Check that options for method1 were merged.
    Map<String, Duration> method1OpsValues = localActivity.localActivity1();
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), method1OpsValues.get("ScheduleToCloseTimeout"));
    Assert.assertEquals(
        methodOps2.getStartToCloseTimeout(), method1OpsValues.get("StartToCloseTimeout"));

    // Check that options for method2 were default.
    Map<String, Duration> method2OpsValues = localActivity.localActivity2();
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), method2OpsValues.get("ScheduleToCloseTimeout"));
    Assert.assertEquals(
        defaultOps.getStartToCloseTimeout(), method2OpsValues.get("StartToCloseTimeout"));
  }
}
