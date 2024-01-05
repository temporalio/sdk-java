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

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.getResult
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.KotlinWorkerFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Duration

/** Sample Kotlin Temporal Workflow Definition that executes a couple activities in parallel using coroutines  */
object HelloKotlinWorkflow {
  // Define the task queue name
  const val TASK_QUEUE = "HelloKotlinTaskQueue"

  // Define our workflow unique id
  const val WORKFLOW_ID = "HelloKotlinWorkflow"

  /**
   * With our Workflow and Activities defined, we can now start execution. The main method starts
   * the worker and then the workflow.
   */
  @JvmStatic
  fun main(args: Array<String>) {
    // Get a Workflow service stub.
    val service = WorkflowServiceStubs.newLocalServiceStubs()

    /*
 * Get a Workflow service client which can be used to start, Signal, and Query Workflow Executions.
 */
    val client = WorkflowClient.newInstance(service)

    /*
 * Define the workflow factory. It is used to create workflow workers for a specific task queue.
 */
    val factory = KotlinWorkerFactory(client, null)

    /*
 * Define the workflow worker. Workflow workers listen to a defined task queue and process
 * workflows and activities.
 */
    val worker = factory.newWorker(TASK_QUEUE)

    /*
 * Register our workflow implementation with the worker.
 * Workflow implementations must be known to the worker at runtime in
 * order to dispatch workflow tasks.
 */worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl::class.java)
    /**
     * Register our Activity Types with the Worker. Since Activities are stateless and thread-safe,
     * the Activity Type is a shared instance.
     */
    worker.registerActivitiesImplementations(GreetingActivitiesImpl())

    /*
 * Start all the workers registered for a specific task queue.
 * The started workers then start polling for workflows and activities.
 */factory.start()

    // Create the workflow client stub. It is used to start our workflow execution.
    val workflow = client.newUntypedWorkflowStub(
      "GreetingWorkflow",
      WorkflowOptions.newBuilder()
        .setWorkflowId(WORKFLOW_ID)
        .setTaskQueue(TASK_QUEUE)
        .build()
    )

    /*
 * Execute our workflow and wait for it to complete. The call to our getGreeting method is
 * synchronous.
 *
 * See {@link io.temporal.samples.hello.HelloSignal} for an example of starting workflow
 * without waiting synchronously for its result.
 */
    workflow.start("Kotlin")
    val greeting = workflow.getResult<String>()
    // Display workflow execution results
    println(greeting)
    System.exit(0)
  }

  /**
   * The Workflow Definition's Interface must contain one method annotated with @WorkflowMethod.
   *
   *
   * Workflow Definitions should not contain any heavyweight computations, non-deterministic
   * code, network calls, database operations, etc. Those things should be handled by the
   * Activities.
   *
   * @see io.temporal.workflow.WorkflowInterface
   *
   * @see io.temporal.workflow.WorkflowMethod
   */
  @WorkflowInterface
  interface GreetingWorkflow {
    /**
     * This is the method that is executed when the Workflow Execution is started. The Workflow
     * Execution completes when this method finishes execution.
     */
    @WorkflowMethod
    suspend fun getGreeting(name: String?): String
  }

  /**
   * This is the Activity Definition's Interface. Activities are building blocks of any Temporal
   * Workflow and contain any business logic that could perform long running computation, network
   * calls, etc.
   *
   *
   * Annotating Activity Definition methods with @ActivityMethod is optional.
   *
   * @see io.temporal.activity.ActivityInterface
   *
   * @see io.temporal.activity.ActivityMethod
   */
  @ActivityInterface
  interface GreetingActivities {
    // Define your activity method which can be called during workflow execution
    @ActivityMethod(name = "greet")
    fun composeGreeting(greeting: String?, name: String?): String
  }

  // Define the workflow implementation which implements our getGreeting workflow method.
  class GreetingWorkflowImpl : GreetingWorkflow {

    override suspend fun getGreeting(name: String?): String = coroutineScope {
      val activities = KotlinWorkflow.newUntypedActivityStub(
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(2)).build()
      )

      val result1 = async { activities.execute("greet", String::class.java, "Hello", name!!) }
      val result2 = async {
        delay(1000)
        activities.execute("greet", String::class.java, "Bye", name!!)
      }
      return@coroutineScope result1.await()!! + "\n" + result2.await()!!
    }
  }

  /** Simple activity implementation, that concatenates two strings.  */
  internal class GreetingActivitiesImpl : GreetingActivities {
    override fun composeGreeting(greeting: String?, name: String?): String {
      log.info("Composing greeting...")
      return greeting + " " + name + "!"
    }

    companion object {
      private val log = LoggerFactory.getLogger(GreetingActivitiesImpl::class.java)
    }
  }
}
