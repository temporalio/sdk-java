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

package io.temporal.testing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;

public class TestWorkflowExtensionTest {

  @RegisterExtension
  public static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder().setWorkflowTypes(HelloWorkflowImpl.class).build();

  @WorkflowInterface
  public interface HelloWorkflow {
    @WorkflowMethod
    void sayHello(String name);
  }

  public static class HelloWorkflowImpl implements HelloWorkflow {

    private static final Logger logger = Workflow.getLogger(HelloWorkflowImpl.class);

    @Override
    public void sayHello(String name) {
      logger.info("Hello...");
      Workflow.sleep(Duration.ofMinutes(1));
      logger.info("Hello, {}", name);
    }
  }

  @Test
  public void extensionShouldLaunchTestEnvironmentAndResolveParameters(
      TestWorkflowEnvironment testEnv,
      WorkflowClient workflowClient,
      WorkflowOptions workflowOptions,
      Worker worker,
      HelloWorkflow workflow) {

    assertAll(
        () -> assertTrue(testEnv.isStarted()),
        () -> assertNotNull(workflowClient),
        () -> assertNotNull(workflowOptions.getTaskQueue()),
        () -> assertNotNull(worker),
        () -> workflow.sayHello("World"));
  }
}
