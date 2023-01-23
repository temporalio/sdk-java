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

package io.temporal.client.functional;

import static org.junit.Assert.assertEquals;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Rule;
import org.junit.Test;

public class QueryAfterStartFollowsRunsChainTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Test
  public void testQueryAfterStartFollowsRunsChain() {
    TestWorkflows.TestWorkflowWithQuery workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.TestWorkflowWithQuery.class);
    assertEquals("done", workflow.execute());
    assertEquals(
        "We expect query call to use the last run of the workflow, not the first one that we started by this stub",
        "2",
        workflow.query());
  }

  public static class TestWorkflowImpl implements TestWorkflows.TestWorkflowWithQuery {
    private final String status =
        Workflow.getInfo().getContinuedExecutionRunId().isPresent() ? "2" : "1";

    @Override
    public String execute() {
      if (!Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
        Workflow.continueAsNew();
      }
      return "done";
    }

    @Override
    public String query() {
      return status;
    }
  }
}
