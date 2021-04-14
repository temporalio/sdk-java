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
import java.time.Duration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ActivityMethodOptionsTest {

  private static final ActivityOptions defaultOps =
      ActivityOptions.newBuilder()
          .setTaskQueue("ActivityOptions")
          .setHeartbeatTimeout(Duration.ofSeconds(1))
          .setScheduleToStartTimeout(Duration.ofSeconds(2))
          .setScheduleToCloseTimeout(Duration.ofDays(1))
          .setStartToCloseTimeout(Duration.ofSeconds(2))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
          .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
          .setContextPropagators(null)
          .build();
  private static final ActivityOptions methodOps1 =
      ActivityOptions.newBuilder()
          .setTaskQueue("ActivityMethodOptions")
          .setHeartbeatTimeout(Duration.ofSeconds(3))
          .setScheduleToStartTimeout(Duration.ofSeconds(3))
          .setScheduleToCloseTimeout(Duration.ofDays(3))
          .setStartToCloseTimeout(Duration.ofSeconds(3))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
          .setCancellationType(ActivityCancellationType.TRY_CANCEL)
          .setContextPropagators(null)
          .build();
  private static final ActivityOptions methodOps2 =
      ActivityOptions.newBuilder()
          .setHeartbeatTimeout(Duration.ofSeconds(7))
          .setStartToCloseTimeout(Duration.ofSeconds(7))
          .build();
  private static final Map<String, ActivityOptions> perMethodOptionsMap =
      new HashMap<String, ActivityOptions>() {
        {
          put("Method1", methodOps2);
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
    testEnv.registerActivitiesImplementations(new ActivityImpl());
    TestActivity activity =
        testEnv.newActivityStub(TestActivity.class, defaultOps, perMethodOptionsMap);

    // Check that options for method1 were merged.
    Map<String, String> method1OpsValues = activity.method1();
    Assert.assertEquals(
        methodOps2.getHeartbeatTimeout(), Duration.parse(method1OpsValues.get("HeartbeatTimeout")));
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(),
        Duration.parse(method1OpsValues.get("ScheduleToCloseTimeout")));
    Assert.assertEquals(
        methodOps2.getStartToCloseTimeout(),
        Duration.parse(method1OpsValues.get("StartToCloseTimeout")));

    // Check that options for method2 were default.
    Map<String, String> method2OpsValues = activity.method2();
    Assert.assertEquals(
        defaultOps.getHeartbeatTimeout(), Duration.parse(method2OpsValues.get("HeartbeatTimeout")));
    Assert.assertEquals(
        defaultOps.getScheduleToCloseTimeout(),
        Duration.parse(method2OpsValues.get("ScheduleToCloseTimeout")));
    Assert.assertEquals(
        defaultOps.getStartToCloseTimeout(),
        Duration.parse(method2OpsValues.get("StartToCloseTimeout")));
  }

  @ActivityInterface
  public interface TestActivity {

    @ActivityMethod
    Map<String, String> method1();

    @ActivityMethod
    Map<String, String> method2();
  }

  private static class ActivityImpl implements TestActivity {

    @Override
    public Map<String, String> method1() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      Hashtable<String, String> result =
          new Hashtable<String, String>() {
            {
              put("HeartbeatTimeout", info.getHeartbeatTimeout().toString());
              put("ScheduleToCloseTimeout", info.getScheduleToCloseTimeout().toString());
              put("StartToCloseTimeout", info.getStartToCloseTimeout().toString());
            }
          };
      return result;
    }

    @Override
    public Map<String, String> method2() {
      ActivityInfo info = Activity.getExecutionContext().getInfo();
      Hashtable<String, String> result =
          new Hashtable<String, String>() {
            {
              put("HeartbeatTimeout", info.getHeartbeatTimeout().toString());
              put("ScheduleToCloseTimeout", info.getScheduleToCloseTimeout().toString());
              put("StartToCloseTimeout", info.getStartToCloseTimeout().toString());
            }
          };
      return result;
    }
  }
}
