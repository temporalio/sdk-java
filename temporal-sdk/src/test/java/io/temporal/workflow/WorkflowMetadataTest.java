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

import io.temporal.api.sdk.v1.WorkflowDefinition;
import io.temporal.api.sdk.v1.WorkflowInteractionDefinition;
import io.temporal.api.sdk.v1.WorkflowMetadata;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowMetadataTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowWithMetadataImpl.class).build();

  @Test
  public void testGetMetadata() {
    TestWorkflowWithMetadata workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowWithMetadata.class);
    String result = workflowStub.execute("current details");
    Assert.assertEquals("current details", result);
    WorkflowMetadata metadata =
        WorkflowStub.fromTyped(workflowStub)
            .query(WorkflowClient.QUERY_TYPE_WORKFLOW_METADATA, WorkflowMetadata.class);
    Assert.assertEquals("current details", metadata.getCurrentDetails());
    WorkflowDefinition definition = metadata.getDefinition();
    Assert.assertEquals("TestWorkflowWithMetadata", definition.getType());
    // Check query definitions and order
    Assert.assertEquals(5, definition.getQueryDefinitionsCount());
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder()
            .setName(WorkflowClient.QUERY_TYPE_STACK_TRACE)
            .setDescription("Current stack trace")
            .build(),
        definition.getQueryDefinitions(0));
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder()
            .setName(WorkflowClient.QUERY_TYPE_WORKFLOW_METADATA)
            .setDescription("Metadata about the workflow")
            .build(),
        definition.getQueryDefinitions(1));
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder().setDescription("Dynamic query handler").build(),
        definition.getQueryDefinitions(2));
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder().setName("query").build(),
        definition.getQueryDefinitions(3));
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder()
            .setName("queryWithDescription")
            .setDescription("queryWithDescription description")
            .build(),
        definition.getQueryDefinitions(4));
    // Check signal definitions and order
    Assert.assertEquals(3, definition.getSignalDefinitionsCount());
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder().setDescription("Dynamic signal handler").build(),
        definition.getSignalDefinitions(0));
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder().setName("signal").build(),
        definition.getSignalDefinitions(1));
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder()
            .setName("signalWithDescription")
            .setDescription("signalWithDescription description")
            .build(),
        definition.getSignalDefinitions(2));
    // Check update definitions and order
    Assert.assertEquals(3, definition.getUpdateDefinitionsCount());
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder().setDescription("Dynamic update handler").build(),
        definition.getUpdateDefinitions(0));
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder().setName("update").build(),
        definition.getUpdateDefinitions(1));
    Assert.assertEquals(
        WorkflowInteractionDefinition.newBuilder()
            .setName("updateWithDescription")
            .setDescription("updateWithDescription description")
            .build(),
        definition.getUpdateDefinitions(2));
  }

  @WorkflowInterface
  public interface TestWorkflowWithMetadata {
    @WorkflowMethod
    String execute(String arg);

    @SignalMethod
    void signal(String value);

    @QueryMethod
    String query();

    @UpdateMethod
    void update(String value);

    @SignalMethod(description = "signalWithDescription description")
    void signalWithDescription(String value);

    @QueryMethod(description = "queryWithDescription description")
    String queryWithDescription();

    @UpdateMethod(description = "updateWithDescription description")
    void updateWithDescription(String value);
  }

  public static class TestWorkflowWithMetadataImpl implements TestWorkflowWithMetadata {

    @Override
    public String execute(String details) {
      Workflow.setCurrentDetails(details);
      Workflow.registerListener((DynamicSignalHandler) (signalName, encodedArgs) -> {});
      Workflow.registerListener((DynamicQueryHandler) (queryType, encodedArgs) -> null);
      Workflow.registerListener((DynamicUpdateHandler) (updateType, encodedArgs) -> null);
      return Workflow.getCurrentDetails();
    }

    @Override
    public void signal(String value) {}

    @Override
    public String query() {
      return null;
    }

    @Override
    public void update(String value) {}

    @Override
    public void signalWithDescription(String value) {}

    @Override
    public String queryWithDescription() {
      return null;
    }

    @Override
    public void updateWithDescription(String value) {}
  }
}
