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

package io.temporal.workflow;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedWorkflowMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(WorkflowMethodWithOverrideNameImpl.class));
    Assert.assertEquals(
        "workflow name \"__temporal_workflow\" must not start with \"__temporal_\"",
        e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(WorkflowMethodRestrictedImpl.class));
    Assert.assertEquals(
        "workflow name \"__temporal_workflow\" must not start with \"__temporal_\"",
        e.getMessage());
  }

  @WorkflowInterface
  public interface WorkflowMethodWithOverrideNameRestricted {
    @WorkflowMethod(name = "__temporal_workflow")
    void workflowMethod();
  }

  public static class WorkflowMethodWithOverrideNameImpl
      implements WorkflowMethodWithOverrideNameRestricted {

    @Override
    public void workflowMethod() {}
  }

  @WorkflowInterface
  public interface __temporal_workflow {
    @WorkflowMethod
    void workflowMethod();
  }

  public static class WorkflowMethodRestrictedImpl implements __temporal_workflow {
    @Override
    public void workflowMethod() {}
  }
}
