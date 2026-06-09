package io.temporal.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

class SagaExtTest {

  companion object {
    val compensated = AtomicBoolean(false)
  }

  @Rule
  @JvmField
  val testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(SagaWorkflowImpl::class.java)
    .setActivityImplementations(CompensationActivityImpl())
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  @ActivityInterface
  interface CompensationActivity {
    @ActivityMethod
    fun compensate()
  }

  class CompensationActivityImpl : CompensationActivity {
    override fun compensate() {
      compensated.set(true)
    }
  }

  @WorkflowInterface
  interface SagaWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class SagaWorkflowImpl : SagaWorkflow {
    override fun execute() {
      val activity = Workflow.newActivityStub(
        CompensationActivity::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build()
      )
      val saga = Saga { setParallelCompensation(false) }
      try {
        saga.addCompensation { activity.compensate() }
        throw RuntimeException("simulated failure")
      } catch (e: Exception) {
        saga.compensate()
      }
    }
  }

  @Test
  fun `Saga DSL extension should build Saga with options and run compensations`() {
    compensated.set(false)
    val client = testWorkflowRule.workflowClient
    val stub = client.newWorkflowStub(
      SagaWorkflow::class.java,
      WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    )
    stub.execute()
    assertTrue("compensation should have been called", compensated.get())
  }
}
