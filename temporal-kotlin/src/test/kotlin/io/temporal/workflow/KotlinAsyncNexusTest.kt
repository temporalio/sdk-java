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

package io.temporal.workflow

import com.google.protobuf.ByteString
import io.nexusrpc.Operation
import io.nexusrpc.Service
import io.nexusrpc.handler.OperationContext
import io.nexusrpc.handler.OperationHandler
import io.nexusrpc.handler.OperationImpl
import io.nexusrpc.handler.OperationStartDetails
import io.nexusrpc.handler.ServiceImpl
import io.nexusrpc.handler.SynchronousOperationFunction
import io.temporal.api.common.v1.Payload
import io.temporal.api.nexus.v1.Endpoint
import io.temporal.api.nexus.v1.EndpointSpec
import io.temporal.api.nexus.v1.EndpointTarget
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest
import io.temporal.api.operatorservice.v1.CreateNexusEndpointResponse
import io.temporal.api.operatorservice.v1.DeleteNexusEndpointRequest
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.internal.async.FunctionWrappingUtil
import io.temporal.internal.sync.AsyncInternal
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class KotlinAsyncNexusTest {

  @Rule
  @JvmField
  var testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(WorkflowImpl::class.java)
    .setNexusServiceImplementation(TestNexusServiceImpl())
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  private var endpoint: Endpoint? = null

  @Service
  interface TestNexusService {
    @Operation
    fun operation(): String?
  }

  @ServiceImpl(service = TestNexusService::class)
  class TestNexusServiceImpl {
    @OperationImpl
    fun operation(): OperationHandler<Void, String> {
      // Implemented inline
      return OperationHandler.sync<Void, String>(
        SynchronousOperationFunction<Void, String> { ctx: OperationContext, details: OperationStartDetails, _: Void? -> "Hello Kotlin" }
      )
    }
  }

  @WorkflowInterface
  interface TestWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class WorkflowImpl : TestWorkflow {
    override fun execute() {
      val nexusService = Workflow.newNexusServiceStub(
        TestNexusService::class.java,
        NexusServiceOptions {
          setEndpoint("test-endpoint-" + Workflow.getInfo().taskQueue)
          setOperationOptions(
            NexusOperationOptions {
              setScheduleToCloseTimeout(Duration.ofSeconds(10))
            }
          )
        }
      )
      assertTrue(
        "This has to be true to make Async.function(nexusService::operation) work correctly as expected",
        AsyncInternal.isAsync(nexusService::operation)
      )
      assertTrue(
        "This has to be true to make Async.function(nexusService::operation) work correctly as expected",
        AsyncInternal.isAsync(FunctionWrappingUtil.temporalJavaFunctionalWrapper(nexusService::operation))
      )
      Async.function(nexusService::operation).get()
    }
  }

  @Test
  fun asyncNexusWorkflowTest() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(TestWorkflow::class.java, options)
    workflowStub.execute()
  }

  @Before
  fun setUp() {
    endpoint = createTestEndpoint(
      getTestEndpointSpecBuilder("test-endpoint-" + testWorkflowRule.taskQueue)
    )
  }

  @After
  fun tearDown() {
    testWorkflowRule
      .testEnvironment
      .operatorServiceStubs
      .blockingStub()
      .deleteNexusEndpoint(
        DeleteNexusEndpointRequest.newBuilder()
          .setId(endpoint!!.id)
          .setVersion(endpoint!!.version)
          .build()
      )
  }

  private fun getTestEndpointSpecBuilder(name: String): EndpointSpec.Builder {
    return EndpointSpec.newBuilder()
      .setName(name)
      .setDescription(Payload.newBuilder().setData(ByteString.copyFromUtf8("test endpoint")))
      .setTarget(
        EndpointTarget.newBuilder()
          .setWorker(
            EndpointTarget.Worker.newBuilder()
              .setNamespace(testWorkflowRule.testEnvironment.namespace)
              .setTaskQueue(testWorkflowRule.taskQueue)
          )
      )
  }

  private fun createTestEndpoint(spec: EndpointSpec.Builder): Endpoint {
    val resp: CreateNexusEndpointResponse = testWorkflowRule
      .testEnvironment
      .operatorServiceStubs
      .blockingStub()
      .createNexusEndpoint(CreateNexusEndpointRequest.newBuilder().setSpec(spec).build())
    return resp.endpoint
  }
}
