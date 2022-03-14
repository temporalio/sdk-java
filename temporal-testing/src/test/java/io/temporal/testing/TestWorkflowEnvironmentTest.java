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

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

public class TestWorkflowEnvironmentTest {

  @Rule
  public TestWatcher watchman =
      new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description description) {
          if (testEnv != null) {
            System.err.println(testEnv.getDiagnostics());
            testEnv.close();
          }
        }
      };

  private TestWorkflowEnvironment testEnv;

  @Before
  public void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
    testEnv.start();
  }

  @After
  public void tearDown() {
    testEnv.close();
  }

  @Test
  public void testGetExecutionHistoryWhenWorkflowNotFound() {
    Status status =
        Assert.assertThrows(
                StatusRuntimeException.class,
                () -> {
                  GetWorkflowExecutionHistoryRequest request =
                      GetWorkflowExecutionHistoryRequest.newBuilder()
                          .setExecution(
                              WorkflowExecution.newBuilder()
                                  .setWorkflowId("does not exist")
                                  .build())
                          .build();
                  testEnv
                      .getWorkflowServiceStubs()
                      .blockingStub()
                      .getWorkflowExecutionHistory(request);
                })
            .getStatus();

    Assert.assertEquals(Status.Code.NOT_FOUND, status.getCode());
  }
}
