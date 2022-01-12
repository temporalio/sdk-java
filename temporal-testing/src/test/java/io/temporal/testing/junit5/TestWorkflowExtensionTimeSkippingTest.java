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

package io.temporal.testing.junit5;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInfo;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowExtensionTimeSkippingTest {
  @RegisterExtension
  public static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .setWorkflowTypes(HelloWorkflowImpl.class)
          .setActivityImplementations(new HelloActivityImpl())
          .setUseTimeskipping(false)
          .build();

  @ActivityInterface
  public interface HelloActivity {
    String buildGreeting(String name);
  }

  public static class HelloActivityImpl implements TestWorkflowExtensionTest.HelloActivity {
    @Override
    public String buildGreeting(String name) {
      ActivityInfo activityInfo = Activity.getExecutionContext().getInfo();
      return String.format(
          "Hello %s from activity %s and workflow %s",
          name, activityInfo.getActivityType(), activityInfo.getWorkflowType());
    }
  }

  @WorkflowInterface
  public interface HelloWorkflow {
    @WorkflowMethod
    String sayHello(String name);
  }

  public static class HelloWorkflowImpl implements HelloWorkflow {

    private static final Logger logger =
        Workflow.getLogger(TestWorkflowExtensionTest.HelloWorkflowImpl.class);

    private final TestWorkflowExtensionTest.HelloActivity helloActivity =
        Workflow.newActivityStub(
            TestWorkflowExtensionTest.HelloActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(1)).build());

    @Override
    public String sayHello(String name) {
      logger.info("Hello, {}", name);
      Workflow.sleep(Duration.ofSeconds(1));
      return helloActivity.buildGreeting(name);
    }
  }

  @Test
  public void testCheckNoTimeSkipping(
      TestWorkflowEnvironment testEnv,
      WorkflowClient workflowClient,
      WorkflowOptions workflowOptions,
      Worker worker,
      HelloWorkflow workflow) {

    Assert.assertFalse(testEnv.getTestEnvironmentOptions().isUseTimeskipping());
  }
}
