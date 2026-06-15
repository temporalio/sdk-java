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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class SagaExtTest {

  companion object {
    val compensated = AtomicBoolean(false)
    val compensationOrder = CopyOnWriteArrayList<Int>()
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
    fun compensate(step: Int)
  }

  class CompensationActivityImpl : CompensationActivity {
    override fun compensate(step: Int) {
      compensated.set(true)
      compensationOrder.add(step)
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
      // setParallelCompensation(false) means compensations run in reverse-add order (LIFO)
      val saga = Saga { setParallelCompensation(false) }
      try {
        saga.addCompensation { activity.compensate(1) }
        saga.addCompensation { activity.compensate(2) }
        throw RuntimeException("simulated failure")
      } catch (e: Exception) {
        saga.compensate()
      }
    }
  }

  @Test
  fun `Saga DSL extension should build Saga with options and run compensations`() {
    compensated.set(false)
    compensationOrder.clear()
    val client = testWorkflowRule.workflowClient
    val stub = client.newWorkflowStub(
      SagaWorkflow::class.java,
      WorkflowOptions { setTaskQueue(testWorkflowRule.taskQueue) }
    )
    stub.execute()
    assertTrue("compensation should have been called", compensated.get())
    // setParallelCompensation(false) runs compensations in reverse (LIFO) order
    assertEquals("compensations should run in reverse order", listOf(2, 1), compensationOrder.toList())
  }
}
