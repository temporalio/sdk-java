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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowOptionalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(OptionalWorkflowImpl.class).build();

  @Test
  public void testOptionalArgumentsWorkflow() throws InterruptedException {
    TestWorkflows.OptionalCustomerWorkflow client =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.OptionalCustomerWorkflow.class);
    Optional<TestWorkflows.Customer> customer1 =
        Optional.of(new TestWorkflows.Customer("john", "smith", 33, Optional.of("555-55-5555")));
    Optional<TestWorkflows.Customer> customer2 =
        Optional.of(new TestWorkflows.Customer("merry", "smith", 29, Optional.of("111-11-1111")));
    WorkflowClient.start(client::execute, customer1);
    testWorkflowRule.sleep(Duration.ofSeconds(1));

    int queryCount = 100;
    for (int i = 0; i < queryCount; i++) {
      Optional<TestWorkflows.Customer> result = client.getCustomer();
      assertTrue(result.isPresent());
      assertEquals(result.get().getFirstName(), customer1.get().getFirstName());
      if (testWorkflowRule.isUseExternalService()) {
        // Sleep a little bit to avoid server throttling error.
        Thread.sleep(50);
      }
    }
    client.setCustomer(customer2);
    TestWorkflows.Customer finalResult =
        WorkflowStub.fromTyped(client).getResult(TestWorkflows.Customer.class);
    assertEquals(finalResult.getFirstName(), customer2.get().getFirstName());
  }

  public static class OptionalWorkflowImpl implements TestWorkflows.OptionalCustomerWorkflow {
    private Optional<TestWorkflows.Customer> customer;
    CompletablePromise<Optional<TestWorkflows.Customer>> promise = Workflow.newPromise();

    @Override
    public Optional<TestWorkflows.Customer> execute(Optional<TestWorkflows.Customer> customer) {
      this.customer = customer;

      promise.get();

      return this.customer;
    }

    @Override
    public Optional<TestWorkflows.Customer> getCustomer() {
      return customer;
    }

    @Override
    public void setCustomer(Optional<TestWorkflows.Customer> customer) {
      this.customer = customer;
      promise.complete(null);
    }
  }
}
