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

package io.temporal.workflow;

import static org.junit.Assert.*;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.WorkflowServiceException;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class GrpcRetryerFunctionalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setTestTimeoutSeconds(10)
          .setWorkflowTypes(FiveSecondsSleepingWorkflow.class)
          .build();

  private static ScheduledExecutorService scheduledExecutor;

  @BeforeClass
  public static void beforeClass() {
    scheduledExecutor = Executors.newScheduledThreadPool(1);
  }

  @AfterClass
  public static void afterClass() throws Exception {
    scheduledExecutor.shutdownNow();
  }

  /**
   * This test verifies that if GRPC Deadline in GRPC Context is reached even before the request, we
   * return fast and with the correct DEADLINE_EXCEEDED error and we don't try to retry the
   * DEADLINE_EXCEEDED.
   */
  @Test(timeout = 2000)
  public void contextDeadlineExpiredAtStart() {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);

    AtomicReference<Exception> exRef = new AtomicReference<>();
    Context.current()
        .withDeadlineAfter(-1, TimeUnit.MILLISECONDS, scheduledExecutor)
        // create a new deadline that already expired
        .run(
            () -> {
              try {
                workflow.execute();
                fail();
              } catch (Exception e) {
                exRef.set(e);
              }
            });

    WorkflowServiceException exception = (WorkflowServiceException) exRef.get();
    Throwable cause = exception.getCause();

    assertTrue(cause instanceof StatusRuntimeException);
    assertEquals(
        Status.DEADLINE_EXCEEDED.getCode(), ((StatusRuntimeException) cause).getStatus().getCode());
  }

  /**
   * This test verifies that if GRPC Deadline reached in the middle of workflow execution, we return
   * fast and with the correct DEADLINE_EXCEEDED error and we don't try to retry the
   * DEADLINE_EXCEEDED.
   */
  @Test(timeout = 2500)
  public void contextDeadlineExpiredBeforeWorkflowFinish() {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);

    AtomicReference<Exception> exRef = new AtomicReference<>();
    Context.current()
        .withDeadlineAfter(500, TimeUnit.MILLISECONDS, scheduledExecutor)
        // create a deadline that expires before workflow finishes
        .run(
            () -> {
              try {
                workflow.execute();
                fail();
              } catch (Exception e) {
                exRef.set(e);
              }
            });

    WorkflowServiceException exception = (WorkflowServiceException) exRef.get();
    Throwable cause = exception.getCause();

    assertTrue(cause instanceof StatusRuntimeException);
    assertEquals(
        Status.DEADLINE_EXCEEDED.getCode(), ((StatusRuntimeException) cause).getStatus().getCode());
  }

  public static class FiveSecondsSleepingWorkflow implements TestWorkflows.NoArgsWorkflow {

    @Override
    public void execute() {
      try {
        Thread.sleep(5000);
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
