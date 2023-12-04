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

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.TestWorkflowExtension;
import io.temporal.testing.WorkflowInitialTime;
import io.temporal.testing.junit5.workflowImplementationOptions.TestWorkflowImplementationOptionsCommon.HelloActivityImpl;
import io.temporal.testing.junit5.workflowImplementationOptions.TestWorkflowImplementationOptionsCommon.HelloWorkflowImpl;
import io.temporal.worker.Worker;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class TestWorkflowImplementationOptionsViceVersa {

  @RegisterExtension
  public static final TestWorkflowExtension testWorkflow =
      TestWorkflowExtension.newBuilder()
          .registerWorkflowImplementationTypes(HelloWorkflowImpl.class)
          .setActivityImplementations(new HelloActivityImpl())
          .setInitialTime(Instant.parse("2021-10-10T10:01:00Z"))
          .build();

  @Test
  @WorkflowInitialTime("2020-01-01T01:00:00Z")
  public void extensionShouldNotBeAbleToCallActivityBasedOnMissingTimeouts(
      TestWorkflowEnvironment testEnv, WorkflowOptions workflowOptions, Worker worker)
      throws InterruptedException, ExecutionException, TimeoutException {

    WorkflowStub cut =
        testEnv.getWorkflowClient().newUntypedWorkflowStub("HelloWorkflow", workflowOptions);
    cut.start("World");

    CompletableFuture<String> resultAsync = cut.getResultAsync(String.class);
    assertThrows(TimeoutException.class, () -> resultAsync.get(5, TimeUnit.SECONDS));
  }
}
