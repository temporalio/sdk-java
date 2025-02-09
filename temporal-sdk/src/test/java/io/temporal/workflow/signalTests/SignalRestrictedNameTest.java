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

package io.temporal.workflow.signalTests;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SignalRestrictedNameTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testRegisteringRestrictedSignalMethod() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(
                        SignalMethodWithOverrideNameRestrictedImpl.class));
    Assert.assertEquals(
        "signal name \"__temporal_signal\" must not start with \"__temporal_\"", e.getMessage());

    e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(SignalMethodNameRestrictedImpl.class));
    Assert.assertEquals(
        "signal name \"__temporal_signal\" must not start with \"__temporal_\"", e.getMessage());
  }

  @WorkflowInterface
  public interface SignalMethodWithOverrideNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @SignalMethod(name = "__temporal_signal")
    void signalMethod();
  }

  public static class SignalMethodWithOverrideNameRestrictedImpl
      implements SignalMethodWithOverrideNameRestricted {

    @Override
    public void workflowMethod() {}

    @Override
    public void signalMethod() {}
  }

  @WorkflowInterface
  public interface SignalMethodNameRestricted {
    @WorkflowMethod
    void workflowMethod();

    @SignalMethod()
    void __temporal_signal();
  }

  public static class SignalMethodNameRestrictedImpl implements SignalMethodNameRestricted {
    @Override
    public void workflowMethod() {}

    @Override
    public void __temporal_signal() {}
  }
}
