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

package io.temporal.client.schedules;

import static org.junit.Assume.assumeTrue;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.schedule.v1.ScheduleAction;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.NewWorkflowExecutionInfo;
import io.temporal.api.workflowservice.v1.CreateScheduleRequest;
import io.temporal.api.workflowservice.v1.DeleteScheduleRequest;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.SearchAttributes;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ScheduleWithTypedSearchAttributesTest {
  static final SearchAttributeKey<String> CUSTOM_KEYWORD_SA =
      SearchAttributeKey.forKeyword("CustomKeywordField");
  static final SearchAttributeKey<String> CUSTOM_STRING_SA =
      SearchAttributeKey.forText("CustomStringField");
  static final SearchAttributeKey<Double> CUSTOM_DOUBLE_SA =
      SearchAttributeKey.forDouble("CustomDoubleField");

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(ScheduleWithTypedSearchAttributesTest.QuickWorkflowImpl.class)
          .build();

  private ScheduleClient createScheduleClient() {
    return new ScheduleClientImpl(
        testWorkflowRule.getWorkflowServiceStubs(),
        ScheduleClientOptions.newBuilder()
            .setNamespace(testWorkflowRule.getWorkflowClient().getOptions().getNamespace())
            .setIdentity(testWorkflowRule.getWorkflowClient().getOptions().getIdentity())
            .setDataConverter(testWorkflowRule.getWorkflowClient().getOptions().getDataConverter())
            .build());
  }

  private Schedule.Builder createTestSchedule() {
    SearchAttributes searchAttributes =
        SearchAttributes.newBuilder()
            .set(CUSTOM_KEYWORD_SA, "keyword")
            .set(CUSTOM_STRING_SA, "string")
            .set(CUSTOM_DOUBLE_SA, 0.1)
            .build();

    WorkflowOptions wfOptions =
        WorkflowOptions.newBuilder()
            .setWorkflowId("test-schedule-id")
            .setTaskQueue(testWorkflowRule.getTaskQueue())
            .setMemo(Collections.singletonMap("memokey1", "memoval1"))
            .setTypedSearchAttributes(searchAttributes)
            .build();

    return Schedule.newBuilder()
        .setAction(
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType("TestWorkflowReturnString")
                .setOptions(wfOptions)
                .build())
        .setSpec(
            ScheduleSpec.newBuilder()
                .setIntervals(Arrays.asList(new ScheduleIntervalSpec(Duration.ofSeconds(1))))
                .build());
  }

  @Before
  public void checkRealServer() {
    assumeTrue("skipping for test server", SDKTestWorkflowRule.useExternalService);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void createScheduleWithTypedSearchAttributes() {
    ScheduleClient client = createScheduleClient();
    // Create typed search attributes
    SearchAttributes searchAttributes =
        SearchAttributes.newBuilder().set(CUSTOM_KEYWORD_SA, "keyword").build();
    // Create schedule with typed search attributes
    ScheduleOptions options =
        ScheduleOptions.newBuilder().setTypedSearchAttributes(searchAttributes).build();
    String scheduleId = UUID.randomUUID().toString();
    Schedule schedule = createTestSchedule().build();
    ScheduleHandle handle = client.createSchedule(scheduleId, schedule, options);
    // Verify schedules search attribute
    ScheduleDescription description = handle.describe();
    Assert.assertEquals(scheduleId, description.getId());
    Assert.assertEquals("keyword", description.getTypedSearchAttributes().get(CUSTOM_KEYWORD_SA));
    // Verify the actions search attributes
    ScheduleActionStartWorkflow action =
        (ScheduleActionStartWorkflow) description.getSchedule().getAction();
    Assert.assertEquals(null, action.getOptions().getSearchAttributes());
    Assert.assertEquals(3, action.getOptions().getTypedSearchAttributes().size());
    Assert.assertEquals(
        true, action.getOptions().getTypedSearchAttributes().containsKey(CUSTOM_DOUBLE_SA));
    // Clean up schedule
    handle.delete();
  }

  public void verifyTypedSearchAttributes(
      ScheduleDescription description,
      String customKeywordFieldValue,
      String customStringFieldValue) {
    ScheduleActionStartWorkflow readAction =
        (ScheduleActionStartWorkflow) description.getSchedule().getAction();
    Assert.assertEquals(2, readAction.getOptions().getTypedSearchAttributes().size());

    Payload keywordPayload =
        readAction
            .getOptions()
            .getTypedSearchAttributes()
            .get(SearchAttributeKey.forUntyped("CustomKeywordField"));
    Assert.assertEquals(
        customKeywordFieldValue,
        DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
            keywordPayload, String.class, String.class));

    Payload textPayload =
        readAction
            .getOptions()
            .getTypedSearchAttributes()
            .get(SearchAttributeKey.forUntyped("CustomStringField"));
    Assert.assertEquals(
        customStringFieldValue,
        DefaultDataConverter.STANDARD_INSTANCE.fromPayload(
            textPayload, String.class, String.class));
  }

  @Test
  public void updateExternalSchedule() {
    // Create schedule using raw GRPC api to simulate one created from a different SDK
    io.temporal.api.common.v1.SearchAttributes searchAttributes =
        io.temporal.api.common.v1.SearchAttributes.newBuilder()
            .putIndexedFields(
                "CustomKeywordField",
                DefaultDataConverter.STANDARD_INSTANCE.toPayload("keyword").get())
            .putIndexedFields(
                "CustomStringField", DefaultDataConverter.STANDARD_INSTANCE.toPayload("text").get())
            .build();

    String scheduleId = UUID.randomUUID().toString();
    String workflowId = UUID.randomUUID().toString();
    NewWorkflowExecutionInfo startWorkflow =
        NewWorkflowExecutionInfo.newBuilder()
            .setWorkflowId(workflowId)
            .setTaskQueue(TaskQueue.newBuilder().setName(testWorkflowRule.getTaskQueue()).build())
            .setWorkflowType(WorkflowType.newBuilder().setName("TestWorkflowReturnString").build())
            .setSearchAttributes(searchAttributes)
            .build();
    ScheduleAction action = ScheduleAction.newBuilder().setStartWorkflow(startWorkflow).build();
    io.temporal.api.schedule.v1.Schedule schedule =
        io.temporal.api.schedule.v1.Schedule.newBuilder()
            .setAction(action)
            .setSpec(io.temporal.api.schedule.v1.ScheduleSpec.newBuilder().build())
            .build();
    CreateScheduleRequest request =
        CreateScheduleRequest.newBuilder()
            .setNamespace(SDKTestWorkflowRule.NAMESPACE)
            .setIdentity(testWorkflowRule.getWorkflowClient().getOptions().getIdentity())
            .setRequestId(UUID.randomUUID().toString())
            .setScheduleId(scheduleId)
            .setSchedule(schedule)
            .build();
    testWorkflowRule.getWorkflowServiceStubs().blockingStub().createSchedule(request);
    // Describe the schedule in the Java SDK
    ScheduleClient client = createScheduleClient();
    ScheduleHandle externalSchedule = client.getHandle(scheduleId);
    ScheduleDescription description = externalSchedule.describe();
    Assert.assertEquals(scheduleId, description.getId());
    verifyTypedSearchAttributes(description, "keyword", "text");
    // Update the schedule in the Java SDK
    externalSchedule.update(
        (ScheduleUpdateInput input) -> {
          Schedule.Builder builder = Schedule.newBuilder(input.getDescription().getSchedule());
          ScheduleActionStartWorkflow wfAction =
              ((ScheduleActionStartWorkflow) input.getDescription().getSchedule().getAction());
          WorkflowOptions wfOptions =
              WorkflowOptions.newBuilder(wfAction.getOptions())
                  .setWorkflowTaskTimeout(Duration.ofMinutes(7))
                  .setMemo(Collections.singletonMap("memokey3", "memoval3"))
                  .build();
          builder.setAction(
              ScheduleActionStartWorkflow.newBuilder(wfAction).setOptions(wfOptions).build());
          return new ScheduleUpdate(builder.build());
        });
    // Verify the search attributes stayed the same
    description = externalSchedule.describe();
    Assert.assertEquals(scheduleId, description.getId());
    verifyTypedSearchAttributes(description, "keyword", "text");
    // Update the search attributes in the Java SDK
    externalSchedule.update(
        (ScheduleUpdateInput input) -> {
          Schedule.Builder builder = Schedule.newBuilder(input.getDescription().getSchedule());
          ScheduleActionStartWorkflow wfAction =
              ((ScheduleActionStartWorkflow) input.getDescription().getSchedule().getAction());
          WorkflowOptions wfOptions =
              WorkflowOptions.newBuilder(wfAction.getOptions())
                  .setTypedSearchAttributes(
                      SearchAttributes.newBuilder()
                          .set(
                              SearchAttributeKey.forUntyped("CustomKeywordField"),
                              DefaultDataConverter.STANDARD_INSTANCE.toPayload("new value").get())
                          .set(
                              SearchAttributeKey.forUntyped("CustomStringField"),
                              DefaultDataConverter.STANDARD_INSTANCE
                                  .toPayload("another new value")
                                  .get())
                          .build())
                  .build();
          builder.setAction(
              ScheduleActionStartWorkflow.newBuilder(wfAction).setOptions(wfOptions).build());
          return new ScheduleUpdate(builder.build());
        });
    description = externalSchedule.describe();
    Assert.assertEquals(scheduleId, description.getId());
    verifyTypedSearchAttributes(description, "new value", "another new value");
    // Clean up out manually created schedule
    testWorkflowRule
        .getWorkflowServiceStubs()
        .blockingStub()
        .deleteSchedule(
            DeleteScheduleRequest.newBuilder()
                .setNamespace(SDKTestWorkflowRule.NAMESPACE)
                .setIdentity(testWorkflowRule.getWorkflowClient().getOptions().getIdentity())
                .setScheduleId(scheduleId)
                .build());
  }

  public static class QuickWorkflowImpl implements TestWorkflows.TestWorkflowReturnString {
    @Override
    public String execute() {
      return null;
    }
  }
}
