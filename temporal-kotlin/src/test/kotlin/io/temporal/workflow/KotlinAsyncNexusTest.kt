
package io.temporal.workflow

import io.nexusrpc.Operation
import io.nexusrpc.Service
import io.nexusrpc.handler.OperationContext
import io.nexusrpc.handler.OperationHandler
import io.nexusrpc.handler.OperationImpl
import io.nexusrpc.handler.OperationStartDetails
import io.nexusrpc.handler.ServiceImpl
import io.nexusrpc.handler.SynchronousOperationFunction
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.internal.async.FunctionWrappingUtil
import io.temporal.internal.sync.AsyncInternal
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Assert.assertTrue
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
}
