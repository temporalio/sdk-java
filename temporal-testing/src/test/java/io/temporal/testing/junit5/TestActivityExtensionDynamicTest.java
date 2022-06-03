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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.DynamicActivity;
import io.temporal.common.converter.EncodedValues;
import io.temporal.testing.TestActivityExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class TestActivityExtensionDynamicTest {

  @RegisterExtension
  public static final TestActivityExtension activityExtension =
      TestActivityExtension.newBuilder()
          .setActivityImplementations(new MyDynamicActivityImpl())
          .build();

  @ActivityInterface
  public interface MyActivity {

    @ActivityMethod(name = "OverriddenActivityMethod")
    String activity1(String input);
  }

  private static class MyDynamicActivityImpl implements DynamicActivity {

    @Override
    public Object execute(EncodedValues args) {
      return Activity.getExecutionContext().getInfo().getActivityType()
          + "-"
          + args.get(0, String.class);
    }
  }

  @Test
  public void extensionShouldResolveDynamicActivitiesParameters(MyActivity activity) {
    assertEquals("OverriddenActivityMethod-input1", activity.activity1("input1"));
  }
}
