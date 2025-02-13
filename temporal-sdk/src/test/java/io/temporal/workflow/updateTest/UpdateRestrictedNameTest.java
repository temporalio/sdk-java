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

package io.temporal.workflow.updateTest;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class UpdateRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedUpdateMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(
                        UpdateMethodWithOverrideNameRestrictedImpl.class));
    Assert.assertEquals(
        "update name \"__temporal_update\" must not start with \"__temporal_\"", e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(UpdateMethodNameRestrictedImpl.class));
    Assert.assertEquals(
        "update name \"__temporal_update\" must not start with \"__temporal_\"", e.getMessage());
  }

  @WorkflowInterface
  public interface UpdateMethodWithOverrideNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @UpdateMethod(name = "__temporal_update")
    void updateMethod();
  }

  public static class UpdateMethodWithOverrideNameRestrictedImpl
      implements UpdateMethodWithOverrideNameRestricted {

    @Override
    public void workflowMethod() {}

    @Override
    public void updateMethod() {}
  }

  @WorkflowInterface
  public interface UpdateMethodNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @UpdateMethod()
    void __temporal_update();
  }

  public static class UpdateMethodNameRestrictedImpl implements UpdateMethodNameRestricted {
    @Override
    public void workflowMethod() {}

    @Override
    public void __temporal_update() {}
  }
}
