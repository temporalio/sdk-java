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

package io.temporal.testing.junit5.testWorkflowImplementationOptions;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.temporal.client.WorkflowFailedException;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.testing.WorkflowInitialTime;
import io.temporal.testing.junit5.testWorkflowImplementationOptions.TestWorkflowImplementationOptionsCommon.HelloWorkflow;
import io.temporal.testing.junit5.testWorkflowImplementationOptions.TestWorkflowImplementationOptionsCommon.HelloWorkflowImpl;
import io.temporal.testing.junit5.testWorkflowImplementationOptions.TestWorkflowImplementationOptionsCommon.TestException;
import io.temporal.worker.WorkflowImplementationOptions;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowImplementationOptionsMain {

  static WorkflowImplementationOptions failWorkflowOnTestException() {
    return WorkflowImplementationOptions.newBuilder()
        .setFailWorkflowExceptionTypes(TestException.class)
        .build();
  }

  @RegisterExtension
  public static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(
              failWorkflowOnTestException(), HelloWorkflowImpl.class)
          .setInitialTime(Instant.parse("2021-10-10T10:01:00Z"))
          .build();

  @Test
  @WorkflowInitialTime("2020-01-01T01:00:00Z")
  public void extensionShouldLaunchTestEnvironmentWithWorkflowImplementationOptions(
      HelloWorkflow workflow) {

    assertThrows(WorkflowFailedException.class, () -> workflow.sayHello("World"));
  }
}
