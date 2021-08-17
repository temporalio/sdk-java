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

package io.temporal.workflow.signalTests;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.Rule;
import org.junit.Test;

public class SignalMethodOverloadTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestSignalMethodOverloadImpl.class).build();

  public static class TestSignalMethodOverloadImpl implements TestSignalMethodOverload {
    @Override
    public void execute() {}

    @Override
    public void foo() {}

    @Override
    public void foo(String bar) {}
  }

  @WorkflowInterface
  public interface TestSignalMethodOverload {
    @WorkflowMethod
    void execute();

    @SignalMethod
    void foo();

    @SignalMethod(name = "foobar")
    void foo(String bar);
  }

  // Being able to create a workflow worker and register workflow with two signal methods with the
  // same function name is the test by itself.
  // By doing this we are verifying that java.lang.IllegalArgumentException: Duplicated methods
  // (overloads are not allowed) is not thrown.
  @Test
  public void testSignalMethodOverride() {}
}
