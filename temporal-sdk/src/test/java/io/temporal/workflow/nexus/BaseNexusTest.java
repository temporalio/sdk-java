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

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import io.temporal.api.nexus.v1.EndpointTarget;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointResponse;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import org.junit.After;
import org.junit.Before;

public abstract class BaseNexusTest {

  protected abstract SDKTestWorkflowRule getTestWorkflowRule();

  Endpoint endpoint;

  public static String getEndpointName() {
    return "test-endpoint-" + Workflow.getInfo().getTaskQueue();
  }

  @Before
  public void setUp() {
    endpoint =
        createTestEndpoint(
            getTestEndpointSpecBuilder("test-endpoint-" + getTestWorkflowRule().getTaskQueue()));
  }

  @After
  public void tearDown() {
    getTestWorkflowRule()
        .getTestEnvironment()
        .getOperatorServiceStubs()
        .blockingStub()
        .deleteNexusEndpoint(
            io.temporal.api.operatorservice.v1.DeleteNexusEndpointRequest.newBuilder()
                .setId(endpoint.getId())
                .setVersion(endpoint.getVersion())
                .build());
  }

  private EndpointSpec.Builder getTestEndpointSpecBuilder(String name) {
    return EndpointSpec.newBuilder()
        .setName(name)
        .setDescription(Payload.newBuilder().setData(ByteString.copyFromUtf8("test endpoint")))
        .setTarget(
            EndpointTarget.newBuilder()
                .setWorker(
                    EndpointTarget.Worker.newBuilder()
                        .setNamespace(getTestWorkflowRule().getTestEnvironment().getNamespace())
                        .setTaskQueue(getTestWorkflowRule().getTaskQueue())));
  }

  private Endpoint createTestEndpoint(EndpointSpec.Builder spec) {
    CreateNexusEndpointResponse resp =
        getTestWorkflowRule()
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .createNexusEndpoint(CreateNexusEndpointRequest.newBuilder().setSpec(spec).build());
    return resp.getEndpoint();
  }
}
