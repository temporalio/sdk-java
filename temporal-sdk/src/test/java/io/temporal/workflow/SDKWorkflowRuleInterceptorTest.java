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

import io.temporal.testing.TracingWorkerInterceptor;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class SDKWorkflowRuleInterceptorTest {

  @Rule public SDKTestWorkflowRule testWorkflowRule1 = SDKTestWorkflowRule.newBuilder().build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule2 =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(WorkerFactoryOptions.getDefaultInstance())
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule3 =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(
                      new TracingWorkerInterceptor(new TracingWorkerInterceptor.FilteredTrace()))
                  .build())
          .build();

  @Test
  public void testWorkerInterceptorWorkerFactoryOptionsNotSet() {
    Assert.assertNotNull(testWorkflowRule1.getInterceptor(TracingWorkerInterceptor.class));
  }

  @Test
  public void testWorkerInterceptorWorkerFactoryOptionsSet() {
    Assert.assertNotNull(testWorkflowRule2.getInterceptor(TracingWorkerInterceptor.class));
  }

  @Test
  public void testWorkerInterceptorWorkerFactoryOptionsSetWithInterceptor() {
    Assert.assertNotNull(testWorkflowRule3.getInterceptor(TracingWorkerInterceptor.class));
  }
}
