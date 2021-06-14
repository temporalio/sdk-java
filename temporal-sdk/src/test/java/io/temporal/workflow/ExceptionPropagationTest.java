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

import static org.junit.Assert.*;

import io.temporal.client.WorkflowFailedException;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.worker.WorkflowImplementationOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowStringArg;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ExceptionPropagationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              WorkflowImplementationOptions.newBuilder()
                  .setFailWorkflowExceptionTypes(
                      NumberFormatException.class, FileNotFoundException.class)
                  .build(),
              ThrowingChild.class,
              TestExceptionPropagationImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  /**
   * Test that an NPE thrown in an activity executed from a child workflow results in the following
   * chain of exceptions when an exception is received in an external client that executed workflow
   * through a WorkflowClient:
   *
   * <pre>
   * {@link WorkflowFailedException}
   *     ->{@link ChildWorkflowFailure}
   *         ->{@link ActivityFailure}
   *             ->OriginalActivityException
   * </pre>
   *
   * <p>This test also tests that Checked exception wrapping and unwrapping works producing a nice
   * exception chain without the wrappers.
   */
  @Test
  public void testExceptionPropagation() {
    TestWorkflowStringArg client =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowStringArg.class);
    try {
      client.execute(testWorkflowRule.getTaskQueue());
      Assert.fail("Unreachable");
    } catch (WorkflowFailedException e) {
      // Rethrow the assertion failure
      Throwable c1 = e.getCause();
      Throwable c2 = c1.getCause();
      Throwable c3 = c2.getCause();
      Throwable c4 = c3.getCause();
      Throwable c5 = c4.getCause();
      Throwable c6 = c5.getCause();
      if (c2 instanceof AssertionError) {
        throw (AssertionError) c2;
      }
      assertNoEmptyStacks(e);
      // Uncomment to see the actual trace.
      //            e.printStackTrace();
      Assert.assertTrue(e.getMessage(), e.getMessage().contains("TestWorkflowStringArg"));
      Assert.assertTrue(e.getStackTrace().length > 0);
      Assert.assertTrue(c1 instanceof ApplicationFailure);
      Assert.assertEquals(
          FileNotFoundException.class.getName(), ((ApplicationFailure) c1).getType());
      Assert.assertTrue(c2 instanceof ChildWorkflowFailure);
      Assert.assertTrue(c3 instanceof ApplicationFailure);
      Assert.assertEquals(
          NumberFormatException.class.getName(), ((ApplicationFailure) c3).getType());
      Assert.assertEquals(Throwable.class.getName(), ((ApplicationFailure) c4).getType());
      Assert.assertTrue(c5 instanceof ActivityFailure);
      Assert.assertTrue(c6 instanceof ApplicationFailure);
      Assert.assertEquals(IOException.class.getName(), ((ApplicationFailure) c6).getType());
      Assert.assertEquals(
          "message='simulated IO problem', type='java.io.IOException', nonRetryable=false",
          c6.getMessage());
    }
  }

  private static void assertNoEmptyStacks(RuntimeException e) {
    // Check that there are no empty stacks
    Throwable c = e;
    while (c != null) {
      assertTrue(c.getStackTrace().length > 0);
      c = c.getCause();
    }
  }

  public static class ThrowingChild implements TestWorkflow1 {

    @Override
    @SuppressWarnings("AssertionFailureIgnored")
    public String execute(String taskQueue) {
      VariousTestActivities testActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class,
              TestOptions.newActivityOptions20sScheduleToClose()
                  .toBuilder()
                  .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                  .build());
      try {
        testActivities.throwIO();
        fail("unreachable");
        return "ignored";
      } catch (ActivityFailure e) {
        try {
          assertTrue(e.getMessage().contains("ThrowIO"));
          assertTrue(e.getCause() instanceof ApplicationFailure);
          assertEquals(IOException.class.getName(), ((ApplicationFailure) e.getCause()).getType());
          assertEquals(
              "message='simulated IO problem', type='java.io.IOException', nonRetryable=false",
              e.getCause().getMessage());
        } catch (AssertionError ae) {
          // Errors cause workflow task to fail. But we want workflow to fail in this case.
          throw new RuntimeException(ae);
        }
        Exception ee = new NumberFormatException();
        ee.initCause(new Throwable("simulated throwable", e));
        throw Workflow.wrap(ee);
      }
    }
  }

  public static class TestExceptionPropagationImpl implements TestWorkflowStringArg {

    @Override
    @SuppressWarnings("AssertionFailureIgnored")
    public void execute(String taskQueue) {
      ChildWorkflowOptions options =
          ChildWorkflowOptions.newBuilder().setWorkflowRunTimeout(Duration.ofHours(1)).build();
      TestWorkflow1 child = Workflow.newChildWorkflowStub(TestWorkflow1.class, options);
      try {
        child.execute(taskQueue);
        fail("unreachable");
      } catch (RuntimeException e) {
        Throwable c1 = e.getCause();
        Throwable c2 = c1.getCause();
        Throwable c3 = c2.getCause();
        Throwable c4 = c3.getCause();
        try {
          assertNoEmptyStacks(e);
          assertTrue(e.getMessage().contains("TestWorkflow1"));
          assertTrue(e instanceof ChildWorkflowFailure);
          assertTrue(c1 instanceof ApplicationFailure);
          assertEquals(NumberFormatException.class.getName(), ((ApplicationFailure) c1).getType());
          assertEquals(Throwable.class.getName(), ((ApplicationFailure) c2).getType());
          assertTrue(c3 instanceof ActivityFailure);
          assertTrue(c4 instanceof ApplicationFailure);
          assertEquals(IOException.class.getName(), ((ApplicationFailure) c4).getType());
          assertEquals(
              "message='simulated IO problem', type='java.io.IOException', nonRetryable=false",
              c4.getMessage());
        } catch (AssertionError ae) {
          // Errors cause workflow task to fail. But we want workflow to fail in this case.
          throw new RuntimeException(ae);
        }
        Exception fnf = new FileNotFoundException("simulated exception");
        fnf.initCause(e);
        throw Workflow.wrap(fnf);
      }
    }
  }
}
