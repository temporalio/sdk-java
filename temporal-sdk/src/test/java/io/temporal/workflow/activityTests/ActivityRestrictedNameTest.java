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

package io.temporal.workflow.activityTests;

import io.temporal.activity.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ActivityRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedActivity() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerActivitiesImplementations(new ActivityWithRestrictedNamesImpl()));
    Assert.assertEquals(
        "Activity name \"__temporal_activity\" must not start with \"__temporal_\"",
        e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerActivitiesImplementations(
                        new ActivityWithRestrictedOverrideNameImpl()));
    Assert.assertEquals(
        "Activity name \"__temporal_activity\" must not start with \"__temporal_\"",
        e.getMessage());
  }

  @ActivityInterface
  public interface ActivityWithRestrictedOverrideName {

    @ActivityMethod(name = "__temporal_activity")
    String temporalActivity(String workflowId);
  }

  public static class ActivityWithRestrictedOverrideNameImpl
      implements ActivityWithRestrictedOverrideName {
    @Override
    public String temporalActivity(String workflowId) {
      return null;
    }
  }

  @ActivityInterface
  public interface ActivityWithRestrictedNames {
    String __temporal_activity(String workflowId);
  }

  public static class ActivityWithRestrictedNamesImpl implements ActivityWithRestrictedNames {
    @Override
    public String __temporal_activity(String workflowId) {
      return null;
    }
  }
}
