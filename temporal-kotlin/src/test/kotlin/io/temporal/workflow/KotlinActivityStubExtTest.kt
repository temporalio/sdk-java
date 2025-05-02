
package io.temporal.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.activity.setRetryOptions
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.common.converter.KotlinObjectMapperFactory
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class KotlinActivityStubExtTest {

  @Rule
  @JvmField
  var testWorkflowRule: SDKTestWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setWorkflowTypes(
      SyncWorkflowImpl::class.java,
      AsyncWorkflowImpl::class.java
    )
    .setActivityImplementations(ActivityImpl())
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setDataConverter(DefaultDataConverter(JacksonJsonPayloadConverter(KotlinObjectMapperFactory.new())))
        .build()
    )
    .build()

  @WorkflowInterface
  interface SyncWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class SyncWorkflowImpl : SyncWorkflow {

    override fun execute() {
      val activity = Workflow.newUntypedActivityStub(
        ActivityOptions {
          setStartToCloseTimeout(Duration.ofSeconds(5))
          setRetryOptions { setMaximumAttempts(1) }
        }
      )

      activity.execute<Unit>("Run", "test-argument")
    }
  }

  @WorkflowInterface
  interface AsyncWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class AsyncWorkflowImpl : AsyncWorkflow {
    override fun execute() {
      val activity = Workflow.newUntypedActivityStub(
        ActivityOptions {
          setStartToCloseTimeout(Duration.ofSeconds(5))
          setRetryOptions { setMaximumAttempts(1) }
        }
      )

      val promise = activity.executeAsync<Unit>("Run", "test-argument")
      promise.get()
    }
  }

  @ActivityInterface
  interface TestActivity {
    fun run(arg: String)
  }

  class ActivityImpl : TestActivity {
    override fun run(arg: String) {
    }
  }

  @Test
  fun `execute on untyped activity stub should spread varargs`() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(SyncWorkflow::class.java, options)
    workflowStub.execute()
  }

  @Test
  fun `executeAsync on untyped activity stub should spread varargs`() {
    val client = testWorkflowRule.workflowClient
    val options = WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.taskQueue).build()
    val workflowStub = client.newWorkflowStub(AsyncWorkflow::class.java, options)
    workflowStub.execute()
  }
}
