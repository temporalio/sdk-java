
package io.temporal.workflow

import io.nexusrpc.Operation
import io.nexusrpc.Service
import io.nexusrpc.handler.ServiceImpl
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.nexus.TemporalNexusClient
import io.temporal.nexus.TemporalOperation
import io.temporal.nexus.TemporalOperationResult
import io.temporal.nexus.TemporalOperationStartContext
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class KotlinTemporalOperationTest {

  @Rule
  @JvmField
  var testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(CallerWorkflowImpl::class.java)
    .setNexusServiceImplementation(KotlinSugarServiceImpl())
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  @Service
  interface KotlinSugarService {
    @Operation
    fun greet(input: String): String
  }

  @ServiceImpl(service = KotlinSugarService::class)
  class KotlinSugarServiceImpl {
    @TemporalOperation
    fun greet(
      ctx: TemporalOperationStartContext,
      client: TemporalNexusClient,
      input: String
    ): TemporalOperationResult<String> {
      return TemporalOperationResult.sync("kotlin-$input")
    }
  }

  @WorkflowInterface
  interface CallerWorkflow {
    @WorkflowMethod
    fun execute(arg: String): String
  }

  class CallerWorkflowImpl : CallerWorkflow {
    override fun execute(arg: String): String {
      val stub = Workflow.newNexusServiceStub(
        KotlinSugarService::class.java,
        NexusServiceOptions {
          setOperationOptions(
            NexusOperationOptions {
              setScheduleToCloseTimeout(Duration.ofSeconds(10))
            }
          )
        }
      )
      return stub.greet(arg)
    }
  }

  @Test
  fun temporalOperationSugar_endToEnd() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(CallerWorkflow::class.java, options)
    assertEquals("kotlin-hi", workflowStub.execute("hi"))
  }
}
