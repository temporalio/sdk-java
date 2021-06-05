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

package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestLocalActivityWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testLocalActivity() {
    LocalActivityTestWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(LocalActivityTestWorkflow.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue(), false);
    Assert.assertEquals("test123123", result);
    Assert.assertEquals(activitiesImpl.toString(), 5, activitiesImpl.invocations.size());
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "executeLocalActivity ThrowIO",
            "currentTimeMillis",
            "local activity ThrowIO",
            "local activity ThrowIO",
            "local activity ThrowIO",
            "executeLocalActivity Activity2",
            "currentTimeMillis",
            "local activity Activity2",
            "executeActivity Activity2",
            "activity Activity2");
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    List<HistoryEvent> markers =
        testWorkflowRule.getHistoryEvents(execution, EventType.EVENT_TYPE_MARKER_RECORDED);
    for (HistoryEvent marker : markers) {
      String activityType =
          DataConverter.getDefaultInstance()
              .fromPayloads(
                  0,
                  Optional.of(
                      marker.getMarkerRecordedEventAttributes().getDetailsMap().get("type")),
                  String.class,
                  String.class);
      if (activityType.equals("Activity2")) {
        Optional<Payloads> input =
            Optional.of(marker.getMarkerRecordedEventAttributes().getDetailsMap().get("input"));
        String arg0 =
            DataConverter.getDefaultInstance().fromPayloads(0, input, String.class, String.class);
        assertEquals("test", arg0);
      }
    }
  }

  @Test
  public void testLocalActivityNoInput() {
    LocalActivityTestWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(LocalActivityTestWorkflow.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue(), true);
    Assert.assertEquals("test123123", result);
    Assert.assertEquals(activitiesImpl.toString(), 5, activitiesImpl.invocations.size());
    testWorkflowRule
        .getInterceptor(TracingWorkerInterceptor.class)
        .setExpected(
            "interceptExecuteWorkflow " + SDKTestWorkflowRule.UUID_REGEXP,
            "newThread workflow-method",
            "executeLocalActivity ThrowIO",
            "currentTimeMillis",
            "local activity ThrowIO",
            "local activity ThrowIO",
            "local activity ThrowIO",
            "executeLocalActivity Activity2",
            "currentTimeMillis",
            "local activity Activity2",
            "executeActivity Activity2",
            "activity Activity2");
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    List<HistoryEvent> markers =
        testWorkflowRule.getHistoryEvents(execution, EventType.EVENT_TYPE_MARKER_RECORDED);
    for (HistoryEvent marker : markers) {
      String activityType =
          DataConverter.getDefaultInstance()
              .fromPayloads(
                  0,
                  Optional.of(
                      marker.getMarkerRecordedEventAttributes().getDetailsMap().get("type")),
                  String.class,
                  String.class);
      if (activityType.equals("Activity2")) {
        assertFalse(marker.getMarkerRecordedEventAttributes().getDetailsMap().containsKey("input"));
      }
    }
  }

  @WorkflowInterface
  public interface LocalActivityTestWorkflow {

    @WorkflowMethod
    String execute(String taskQueue, boolean doNotIncludeArgumentsIntoMarker);
  }

  public static class TestLocalActivityWorkflowImpl implements LocalActivityTestWorkflow {
    @Override
    public String execute(String taskQueue, boolean doNotIncludeArgumentsIntoMarker) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class,
              TestOptions.newLocalActivityOptions()
                  .toBuilder()
                  .setDoNotIncludeArgumentsIntoMarker(doNotIncludeArgumentsIntoMarker)
                  .build());
      try {
        localActivities.throwIO();
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
      }

      String laResult = localActivities.activity2("test", 123);
      VariousTestActivities normalActivities =
          Workflow.newActivityStub(
              VariousTestActivities.class, TestOptions.newActivityOptionsForTaskQueue(taskQueue));
      laResult = normalActivities.activity2(laResult, 123);
      return laResult;
    }
  }
}
