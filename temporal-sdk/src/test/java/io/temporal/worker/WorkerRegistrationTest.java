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

package io.temporal.worker;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestNexusServices;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkerRegistrationTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setDoNotStart(true).build();

  @Test
  public void testDuplicateRegistration() {
    Worker worker = testWorkflowRule.getWorker();
    worker.registerNexusServiceImplementation(new TestNexusServiceImpl1());
    Assert.assertThrows(
        TypeAlreadyRegisteredException.class,
        () -> worker.registerNexusServiceImplementation(new TestNexusServiceImpl2()));
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl1 {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, now) -> "Hello " + now);
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl2 {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, now) -> "Hello " + now);
    }
  }
}
