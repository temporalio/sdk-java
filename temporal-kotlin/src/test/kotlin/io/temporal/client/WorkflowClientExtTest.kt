package io.temporal.client

import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.testing.TestWorkflowRule
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class WorkflowClientExtTest {

  @Rule
  @JvmField
  val testWorkflowRule = TestWorkflowRule.newBuilder()
    .setWorkflowTypes(TestWorkflowImpl::class.java)
    .setWorkflowClientOptions(
      WorkflowClientOptions {
        setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
      }
    )
    .build()

  @Test(timeout = 5000)
  fun `signalWithStart extension should work the same as the original method`() {
    val workflowClient = testWorkflowRule.workflowClient

    val typedStub = workflowClient.newWorkflowStub<TestWorkflow> {
      setWorkflowId("1")
      setTaskQueue(testWorkflowRule.taskQueue)
    }
    workflowClient.signalWithStart {
      add { typedStub.start(Duration.ofHours(10)) }
      add { typedStub.collectString("v1") }
    }

    testWorkflowRule.testEnvironment.sleep(Duration.ofHours(1))

    val typedStub2 = workflowClient.newWorkflowStub<TestWorkflow> {
      setWorkflowId("1")
      setTaskQueue(testWorkflowRule.taskQueue)
    }
    workflowClient.signalWithStart {
      add { typedStub2.start(Duration.ofHours(20)) }
      add { typedStub2.collectString("v2") }
    }

    val untypedStub = WorkflowStub.fromTyped(typedStub)

    Assert.assertEquals(listOf("v1", "v2"), untypedStub.getResult<List<String>>())
  }

  @WorkflowInterface
  interface TestWorkflow {
    @WorkflowMethod
    fun start(waitDuration: Duration): List<String>

    @SignalMethod
    fun collectString(arg: String)
  }

  class TestWorkflowImpl : TestWorkflow {
    private val strings = mutableListOf<String>()

    override fun start(waitDuration: Duration): List<String> {
      Workflow.sleep(waitDuration)
      return strings
    }

    override fun collectString(arg: String) {
      strings += arg
    }
  }
}
