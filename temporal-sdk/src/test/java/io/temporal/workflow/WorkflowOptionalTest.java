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
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowOptionalTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CustomerWorkflowWaitForSignalImpl.class, CustomerWorkflowImpl.class)
          .build();

  @Test
  public void testOptionalArgumentsWorkflowTypedStub() {
    Optional<Customer> customer =
        Optional.of(new Customer("john", "smith", 33, Optional.of("555-55-5555")));
    WorkflowClient client = testWorkflowRule.getWorkflowClient();

    CustomerWorkflow workflow =
        client.newWorkflowStub(
            CustomerWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());

    Optional<Customer> result = workflow.execute(customer);
    Assert.assertEquals(customer.get().getFirstName(), result.get().getFirstName());

    // query after completion
    Optional<Customer> queryResult = workflow.getCustomer();
    assertTrue(queryResult.isPresent());
    assertEquals(queryResult.get().getFirstName(), customer.get().getFirstName());
  }

  @Test
  public void testOptionalArgumentsWorkflowUntypedStub() throws InterruptedException {
    CustomerWorkflowWaitForSignal workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(CustomerWorkflowWaitForSignal.class);
    Optional<Customer> customer1 =
        Optional.of(new Customer("john", "smith", 33, Optional.of("555-55-5555")));
    Optional<Customer> customer2 =
        Optional.of(new Customer("merry", "smith", 29, Optional.of("111-11-1111")));
    WorkflowClient.start(workflow::execute, customer1);
    testWorkflowRule.sleep(Duration.ofSeconds(1));

    Optional<Customer> result = workflow.getCustomer();
    assertTrue(result.isPresent());
    assertEquals(result.get().getFirstName(), customer1.get().getFirstName());
    if (testWorkflowRule.isUseExternalService()) {
      // Sleep a little bit to avoid server throttling error.
      Thread.sleep(50);
    }

    // send query to update customer
    workflow.setCustomer(customer2);

    Customer finalResult = WorkflowStub.fromTyped(workflow).getResult(Customer.class);
    assertEquals(finalResult.getFirstName(), customer2.get().getFirstName());
  }

  public static class CustomerWorkflowImpl implements CustomerWorkflow {
    private Optional<Customer> customer;

    @Override
    public Optional<Customer> execute(Optional<Customer> customer) {
      this.customer = customer;
      return this.customer;
    }

    @Override
    public Optional<Customer> getCustomer() {
      return this.customer;
    }
  }

  public static class CustomerWorkflowWaitForSignalImpl implements CustomerWorkflowWaitForSignal {
    private Optional<Customer> customer;
    CompletablePromise<Optional<Customer>> promise = Workflow.newPromise();

    @Override
    public Optional<Customer> execute(Optional<Customer> customer) {
      this.customer = customer;

      promise.get();

      return this.customer;
    }

    @Override
    public Optional<Customer> getCustomer() {
      return customer;
    }

    @Override
    public void setCustomer(Optional<Customer> customer) {
      this.customer = customer;
      promise.complete(null);
    }
  }

  @WorkflowInterface
  public interface CustomerWorkflow {

    @WorkflowMethod
    Optional<Customer> execute(Optional<Customer> customer);

    @QueryMethod
    Optional<Customer> getCustomer();
  }

  @WorkflowInterface
  public interface CustomerWorkflowWaitForSignal {

    @WorkflowMethod
    Optional<Customer> execute(Optional<Customer> customer);

    @QueryMethod
    Optional<Customer> getCustomer();

    @SignalMethod(name = "setCustomer")
    void setCustomer(Optional<Customer> customer);
  }

  public static class Customer {
    private String firstName;
    private String lastName;
    private int age;
    private Optional<String> ssn;

    public Customer() {}

    public Customer(String firstName, String lastName, int age, Optional<String> ssn) {
      this.firstName = firstName;
      this.lastName = lastName;
      this.age = age;
      this.ssn = ssn;
    }

    public String getFirstName() {
      return firstName;
    }

    public void setFirstName(String firstName) {
      this.firstName = firstName;
    }

    public String getLastName() {
      return lastName;
    }

    public void setLastName(String lastName) {
      this.lastName = lastName;
    }

    public int getAge() {
      return age;
    }

    public void setAge(int age) {
      this.age = age;
    }

    public Optional<String> getSsn() {
      return ssn;
    }

    public void setSsn(Optional<String> ssn) {
      this.ssn = ssn;
    }
  }
}
