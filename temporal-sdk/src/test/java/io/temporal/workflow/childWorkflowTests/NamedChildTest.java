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

package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.*;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class NamedChildTest {

  private static final String childReexecuteId = UUID.randomUUID().toString();
  private final TestActivities.TestActivitiesImpl activitiesImpl =
      new TestActivities.TestActivitiesImpl(null);

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNamedChild.class, TestChildReexecuteWorkflow.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testChildAlreadyRunning() {
    WorkflowIdReusePolicyParent client =
        testWorkflowRule.newWorkflowStub200sTimeoutOptions(WorkflowIdReusePolicyParent.class);
    try {
      client.execute(false, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
    }
  }

  @Test
  public void testChildStartTwice() {
    WorkflowIdReusePolicyParent client =
        testWorkflowRule.newWorkflowStub200sTimeoutOptions(WorkflowIdReusePolicyParent.class);
    try {
      client.execute(true, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof ChildWorkflowFailure);
    }
  }

  @Test
  public void testChildReexecute() {
    WorkflowIdReusePolicyParent client =
        testWorkflowRule.newWorkflowStub200sTimeoutOptions(WorkflowIdReusePolicyParent.class);
    assertEquals(
        "HELLO WORLD!",
        client.execute(false, WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE));
  }

  @WorkflowInterface
  public interface WorkflowIdReusePolicyParent {

    @WorkflowMethod
    String execute(boolean parallel, WorkflowIdReusePolicy policy);
  }

  public static class TestNamedChild implements WorkflowTest.ITestNamedChild {

    @Override
    public String execute(String arg) {
      return arg.toUpperCase();
    }
  }

  public static class TestChildReexecuteWorkflow implements WorkflowIdReusePolicyParent {

    public TestChildReexecuteWorkflow() {}

    @Override
    public String execute(boolean parallel, WorkflowIdReusePolicy policy) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder()
              .setWorkflowId(childReexecuteId)
              .setWorkflowIdReusePolicy(policy)
              .build();

      WorkflowTest.ITestNamedChild child1 =
          Workflow.newChildWorkflowStub(WorkflowTest.ITestNamedChild.class, options);
      Promise<String> r1P = Async.function(child1::execute, "Hello ");
      String r1 = null;
      if (!parallel) {
        r1 = r1P.get();
      }
      WorkflowTest.ITestNamedChild child2 =
          Workflow.newChildWorkflowStub(WorkflowTest.ITestNamedChild.class, options);
      ChildWorkflowStub child2Stub = ChildWorkflowStub.fromTyped(child2);
      // Same as String r2 = child2.execute("World!");
      String r2 = child2Stub.execute(String.class, "World!");
      if (parallel) {
        r1 = r1P.get();
      }
      assertEquals(childReexecuteId, Workflow.getWorkflowExecution(child1).get().getWorkflowId());
      assertEquals(childReexecuteId, Workflow.getWorkflowExecution(child2).get().getWorkflowId());
      return r1 + r2;
    }
  }
}
