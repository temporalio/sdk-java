package io.temporal.client

import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.testing.internal.SDKTestWorkflowRule
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WorkflowStubExtTest {

  @Rule
  @JvmField
  val testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(TestWorkflowImpl::class.java)
    .setWorkflowClientOptions(
      WorkflowClientOptions {
        setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
      }
    )
    .build()

  @WorkflowInterface
  interface TestWorkflow {
    @WorkflowMethod
    fun execute(input: String): String
  }

  class TestWorkflowImpl : TestWorkflow {
    override fun execute(input: String) = "result:$input"
  }

  @Test
  fun `getResult reified extension should return typed result`() {
    val client = testWorkflowRule.workflowClient
    val stub = client.newUntypedWorkflowStub(
      "TestWorkflow",
      WorkflowOptions { setTaskQueue(testWorkflowRule.taskQueue) }
    )
    stub.start("hello")
    assertEquals("result:hello", stub.getResult<String>())
  }

  @Test
  fun `getResultAsync reified extension should return typed result via CompletableFuture`() {
    val client = testWorkflowRule.workflowClient
    val stub = client.newUntypedWorkflowStub(
      "TestWorkflow",
      WorkflowOptions { setTaskQueue(testWorkflowRule.taskQueue) }
    )
    stub.start("async")
    assertEquals("result:async", stub.getResultAsync<String>().get())
  }
}
