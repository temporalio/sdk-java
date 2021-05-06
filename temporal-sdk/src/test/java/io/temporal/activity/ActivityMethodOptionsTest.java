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

import io.temporal.testing.TestActivityEnvironment;
import io.temporal.workflow.shared.TestActivities.TestActivity;
import io.temporal.workflow.shared.TestActivities.TestActivityImpl;
import io.temporal.workflow.shared.TestOptions;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ActivityMethodOptionsTest {

  public static final ActivityOptions defaultOps = TestOptions.newActivityOptions1();
  public static final ActivityOptions methodOps1 = TestOptions.newActivityOptions2();
  private static final ActivityOptions methodOps2 =
      TestOptions.newActivityOptions20sScheduleToClose();
  private static final Map<String, ActivityOptions> perMethodOptionsMap =
      new HashMap<String, ActivityOptions>() {
        {
          put("Activity1", methodOps2);
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
    ActivityOptions merged =
        ActivityOptions.newBuilder(defaultOps).mergeActivityOptions(null).build();
    Assert.assertEquals(defaultOps, merged);
    // Assert options were overridden with method options
    merged = ActivityOptions.newBuilder(defaultOps).mergeActivityOptions(methodOps1).build();
    Assert.assertEquals(methodOps1, merged);
  }

  @Test
  public void testActivityMethodOptions() {
    testEnv.registerActivitiesImplementations(new TestActivityImpl());
    TestActivity activity =
        testEnv.newActivityStub(TestActivity.class, defaultOps, perMethodOptionsMap);

    // Check that options for method1 were merged.
    Map<String, Duration> method1OpsValues = activity.activity1();
    Assert.assertEquals(defaultOps.getHeartbeatTimeout(), method1OpsValues.get("HeartbeatTimeout"));
    Assert.assertEquals(
        methodOps2.getScheduleToCloseTimeout(), method1OpsValues.get("ScheduleToCloseTimeout"));
    Assert.assertEquals(
        defaultOps.getStartToCloseTimeout(), method1OpsValues.get("StartToCloseTimeout"));

    // Check that options for method2 were default.
    Map<String, Duration> method2OpsValues = activity.activity2();
    Assert.assertEquals(defaultOps.getHeartbeatTimeout(), method2OpsValues.get("HeartbeatTimeout"));
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(), method2OpsValues.get("ScheduleToCloseTimeout"));
    Assert.assertEquals(
        defaultOps.getStartToCloseTimeout(), method2OpsValues.get("StartToCloseTimeout"));
  }
}
