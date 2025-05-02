
package io.temporal.workflow

import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Rule
import org.junit.Test

class KotlinChildWorkflowStubExtTest {

  @Rule
  @JvmField
  var testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(
      ParentWorkflowImpl::class.java,
      AsyncParentWorkflowImpl::class.java,
      ChildWorkflowImpl::class.java
    )
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  @WorkflowInterface
  interface ChildWorkflow {
    @WorkflowMethod
    fun execute(argument: String): Int
  }

  class ChildWorkflowImpl : ChildWorkflow {
    override fun execute(argument: String): Int {
      return 0
    }
  }

  @WorkflowInterface
  interface ParentWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class ParentWorkflowImpl : ParentWorkflow {
    override fun execute() {
      val childWorkflow = Workflow.newUntypedChildWorkflowStub("ChildWorkflow")
      childWorkflow.execute<Any>("test-argument")
    }
  }

  @WorkflowInterface
  interface AsyncParentWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class AsyncParentWorkflowImpl : AsyncParentWorkflow {
    override fun execute() {
      val childWorkflow = Workflow.newUntypedChildWorkflowStub("ChildWorkflow")
      val promise = childWorkflow.executeAsync<Any>("test-argument")
      promise.get()
    }
  }

  @Test
  fun `execute on untyped child workflow should spread varargs`() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(ParentWorkflow::class.java, options)
    workflowStub.execute()
  }

  @Test
  fun `executeAsync on untyped child workflow should spread varargs`() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(AsyncParentWorkflow::class.java, options)
    workflowStub.execute()
  }
}
