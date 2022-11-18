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

package io.temporal.testing.junit5;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.m3.tally.NoopScope;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.testing.TestActivityEnvironment;
import io.temporal.testing.TestActivityExtension;
import io.temporal.testing.TestEnvironmentOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class TestActivityExtensionTest {

  public static class CustomMetricsScope extends NoopScope {}

  @RegisterExtension
  public static final TestActivityExtension activityExtension =
      TestActivityExtension.newBuilder()
          .setTestEnvironmentOptions(
              TestEnvironmentOptions.newBuilder().setMetricsScope(new CustomMetricsScope()).build())
          .setActivityImplementations(new MyActivityImpl())
          .build();

  @ActivityInterface
  public interface MyActivity {
    @ActivityMethod(name = "OverriddenActivityMethod")
    String activity1(String input);
  }

  private static class MyActivityImpl implements MyActivity {
    @Override
    public String activity1(String input) {
      assertTrue(
          Activity.getExecutionContext().getMetricsScope() instanceof CustomMetricsScope,
          "The custom metrics scope should be available for the activity");
      return Activity.getExecutionContext().getInfo().getActivityType() + "-" + input;
    }
  }

  @Test
  public void extensionShouldLaunchTestEnvironmentAndResolveParameters(
      TestActivityEnvironment testEnv, MyActivity activity) {
    assertAll(
        () -> assertNotNull(testEnv),
        () -> assertEquals("OverriddenActivityMethod-input1", activity.activity1("input1")));
  }
}
