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

package io.temporal.testing.junit5.workflowImplementationOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.temporal.testing.TestWorkflowExtension;
import io.temporal.testing.WorkflowInitialTime;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.junit5.workflowImplementationOptions.TestWorkflowImplementationOptionsCommon.HelloActivityImpl;
import io.temporal.testing.junit5.workflowImplementationOptions.TestWorkflowImplementationOptionsCommon.HelloWorkflow;
import io.temporal.testing.junit5.workflowImplementationOptions.TestWorkflowImplementationOptionsCommon.HelloWorkflowImpl;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowImplementationOptions {

  @RegisterExtension
  public static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(
              SDKTestOptions.newWorkflowImplementationOptionsWithDefaultStartToCloseTimeout(),
              HelloWorkflowImpl.class)
          .setActivityImplementations(new HelloActivityImpl())
          .setInitialTime(Instant.parse("2021-10-10T10:01:00Z"))
          .build();

  @Test
  @WorkflowInitialTime("2020-01-01T01:00:00Z")
  public void extensionShouldLaunchTestEnvironmentWithWorkflowImplementationOptions(
      HelloWorkflow workflow) {

    assertEquals(
        String.format(
            "Hello World from activity BuildGreeting and workflow HelloWorkflow with %s startToCloseTimeout",
            SDKTestOptions.newWorkflowImplementationOptionsWithDefaultStartToCloseTimeout()
                .getDefaultActivityOptions()
                .getStartToCloseTimeout()),
        workflow.sayHello("World"));
  }
}
