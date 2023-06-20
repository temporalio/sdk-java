/*
 *  Copyright (c) 2020 Temporal Technologies, Inc. All Rights Reserved
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.temporal.workflow

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.worker.WorkerFactory
import io.temporal.workflow.HelloActivity.GreetingActivitiesImpl
import org.slf4j.LoggerFactory
import java.time.Duration

/** Sample Temporal Workflow Definition that executes a single Activity.  */
object HelloActivity {
  // Define the task queue name
  const val TASK_QUEUE = "HelloActivityTaskQueue"

  // Define our workflow unique id
  const val WORKFLOW_ID = "HelloActivityWorkflow"

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
    val factory = WorkerFactory.newInstance(client)

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
    val workflow = client.newWorkflowStub(
      GreetingWorkflow::class.java,
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
    val greeting = workflow.getGreeting("World")

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
    fun getGreeting(name: String?): String
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
    /**
     * Define the GreetingActivities stub. Activity stubs are proxies for activity invocations that
     * are executed outside of the workflow thread on the activity worker, that can be on a
     * different host. Temporal is going to dispatch the activity results back to the workflow and
     * unblock the stub as soon as activity is completed on the activity worker.
     *
     *
     * In the [ActivityOptions] definition the "setStartToCloseTimeout" option sets the
     * overall timeout that our workflow is willing to wait for activity to complete. For this
     * example it is set to 2 seconds.
     */
    private val activities = Workflow.newActivityStub(
      GreetingActivities::class.java,
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(2)).build()
    )

    override fun getGreeting(name: String?): String {
      // This is a blocking call that returns only after the activity has completed.
      return activities.composeGreeting("Hello", name)
    }
  }

  /** Simple activity implementation, that concatenates two strings.  */
  internal class GreetingActivitiesImpl : GreetingActivities {
    override fun composeGreeting(greeting: String?, name: String?): String {
      log.info("Composing greeting...")
//      throw ApplicationFailure.newNonRetryableFailure("simulated", "illegal-argument")
      return greeting + " " + name + "!"
    }

    companion object {
      private val log = LoggerFactory.getLogger(GreetingActivitiesImpl::class.java)
    }
  }
}
