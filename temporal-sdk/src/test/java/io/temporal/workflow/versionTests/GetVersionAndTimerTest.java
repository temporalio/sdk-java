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

package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertTrue;

import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import java.time.Duration;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionAndTimerTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleWithoutVersion =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TimedWorkflowWithoutVersionImpl.class)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRuleWithVersion =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TimedWorkflowWithVersionImpl.class).build();

  @Test
  public void testTimedWorkflowWithoutVersionImpl() {
    testTimedWorkflow(testWorkflowRuleWithoutVersion);
  }

  @Test
  public void testTimedWorkflowWithVersionImpl() {
    testTimedWorkflow(testWorkflowRuleWithVersion);
  }

  private void testTimedWorkflow(SDKTestWorkflowRule rule) {
    TimedWorkflow workflowStub = rule.newWorkflowStubTimeoutOptions(TimedWorkflow.class);

    Instant startInstant = Instant.ofEpochMilli(rule.getTestEnvironment().currentTimeMillis());

    Instant endInstant = workflowStub.startAndWait();

    assertTrue(
        "endInstant "
            + endInstant
            + " should be more than 2 hours away from startInstant "
            + startInstant,
        endInstant.isAfter(startInstant.plus(Duration.ofHours(2))));
  }

  @WorkflowInterface
  public interface TimedWorkflow {

    @WorkflowMethod
    Instant startAndWait();
  }

  abstract static class TimedWorkflowImpl implements TimedWorkflow {
    @Override
    public Instant startAndWait() {
      Workflow.newTimer(Duration.ofMinutes(10))
          .thenApply(
              (v) -> {
                getVersion();
                return v;
              });

      Workflow.sleep(Duration.ofHours(1));

      Workflow.newTimer(Duration.ofMinutes(10))
          .thenApply(
              (v) -> {
                getVersion();
                return v;
              });

      Workflow.sleep(Duration.ofHours(1));

      return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }

    protected abstract void getVersion();
  }

  public static class TimedWorkflowWithoutVersionImpl extends TimedWorkflowImpl
      implements TimedWorkflow {

    @Override
    protected void getVersion() {
      // Do nothing
    }
  }

  public static class TimedWorkflowWithVersionImpl extends TimedWorkflowImpl
      implements TimedWorkflow {

    @Override
    protected void getVersion() {
      Workflow.getVersion("id", Workflow.DEFAULT_VERSION, 1);
    }
  }
}
