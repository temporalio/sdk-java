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

package io.temporal.workflow;

import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class PolymorphicStartTest {

  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(WorkflowAImpl.class, WorkflowBImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testPolymorphicStart() {
    WorkflowBase[] stubs =
        new WorkflowBase[] {
          testWorkflowRule.newWorkflowStubTimeoutOptions(WorkflowA.class),
          testWorkflowRule.newWorkflowStubTimeoutOptions(WorkflowB.class)
        };
    String results = stubs[0].execute("0") + ", " + stubs[1].execute("1");
    Assert.assertEquals("WorkflowAImpl0, WorkflowBImpl1", results);
  }

  public interface WorkflowBase {
    @WorkflowMethod
    String execute(String arg);
  }

  @WorkflowInterface
  public interface WorkflowA extends WorkflowBase {}

  @WorkflowInterface
  public interface WorkflowB extends WorkflowBase {}

  public static class WorkflowBImpl implements WorkflowB {
    @Override
    public String execute(String arg) {
      return "WorkflowBImpl" + arg;
    }
  }

  public static class WorkflowAImpl implements WorkflowA {
    @Override
    public String execute(String arg) {
      return "WorkflowAImpl" + arg;
    }
  }
}
