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

package io.temporal.workflow.nexus;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.util.Map;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

// Test the start operation handler receives the correct headers
public class HeaderTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void testOperationHeaders() {
    TestWorkflow workflowStub = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow.class);
    Map<String, String> headers = workflowStub.execute(testWorkflowRule.getTaskQueue());
    Assert.assertTrue(headers.containsKey("operation-timeout"));
    Assert.assertTrue(headers.containsKey("request-timeout"));
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    Map<String, String> execute(String arg);
  }

  public static class TestNexus implements TestWorkflow {
    @Override
    public Map<String, String> execute(String input) {
      // Try to call with the typed stub
      TestNexusService serviceStub = Workflow.newNexusServiceStub(TestNexusService.class);
      return serviceStub.operation();
    }
  }

  @Service
  public interface TestNexusService {
    @Operation
    Map<String, String> operation();
  }

  @ServiceImpl(service = TestNexusService.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<Void, Map<String, String>> operation() {
      return OperationHandler.sync((context, details, input) -> context.getHeaders());
    }
  }
}
