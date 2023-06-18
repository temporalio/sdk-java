package io.temporal.internal.async

import io.temporal.api.common.v1.Payloads
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.api.query.v1.WorkflowQuery
import io.temporal.client.WorkflowClient
import io.temporal.common.context.ContextPropagator
import io.temporal.common.converter.DataConverter
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.internal.replay.ReplayWorkflow
import io.temporal.internal.replay.ReplayWorkflowContext
import io.temporal.internal.replay.WorkflowContext
import io.temporal.internal.statemachines.UpdateProtocolCallback
import io.temporal.internal.sync.*
import io.temporal.internal.worker.WorkflowExecutorCache
import io.temporal.worker.WorkflowImplementationOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*

class KotlinWorkflow(
  private val namespace: String,
  private val workflowExecution: WorkflowExecution,
  private val workflow: KotlinWorkflowDefinition,
  workflowImplementationOptions: WorkflowImplementationOptions?,
  private val dataConverter: DataConverter,
  private val cache: WorkflowExecutorCache,
  private val contextPropagators: List<ContextPropagator>?,
  private val defaultDeadlockDetectionTimeout: Long
) : ReplayWorkflow {

  private val log = LoggerFactory.getLogger(KotlinWorkflow::class.java)

  private val workflowImplementationOptions = workflowImplementationOptions
    ?: WorkflowImplementationOptions.getDefaultInstance()

  private val workflowContext =
    KotlinWorkflowContext(
      namespace,
      workflowExecution,
      this.workflowImplementationOptions,
      dataConverter,
      contextPropagators
    )

  private val dispatcher = TemporalCoroutineDispatcher()
  private val coroutineDispatcher = TemporalCallbackCoroutineDispatcher(dispatcher)
  private val scope = TemporalScope()

  private var executionHandler: KotlinWorkflowExecutionHandler? = null

  override fun start(event: HistoryEvent, context: ReplayWorkflowContext) {
    require(
      !(event.eventType != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        || !event.hasWorkflowExecutionStartedEventAttributes())
    ) { "first event is not WorkflowExecutionStarted, but " + event.eventType }
    val startEvent = event.workflowExecutionStartedEventAttributes
    val workflowType = startEvent.workflowType
    requireNotNull(workflow) { "Unknown workflow type: $workflowType" }
    workflowContext!!.setReplayContext(context)

    executionHandler = KotlinWorkflowExecutionHandler(
      workflowContext, workflow, startEvent, workflowImplementationOptions!!
    )
    // The following order is ensured by this code and DeterministicRunner implementation:
    // 1. workflow.initialize
    // 2. signal handler (if signalWithStart was called)
    // 3. main workflow method
    scope.launch(dispatcher) {
      workflow.initialize()
      async {
        executionHandler!!.runWorkflowMethod()
      }
    }
  }

  override fun handleSignal(signalName: String, input: Optional<Payloads?>?, eventId: Long) {
    scope.launch(coroutineDispatcher) {
      executionHandler!!.handleSignal(signalName, input, eventId)
    }
  }

  override fun handleUpdate(
    updateName: String?,
    input: Optional<Payloads>?,
    eventId: Long,
    callbacks: UpdateProtocolCallback?
  ) {
    TODO("Not yet implemented")
  }

  override fun eventLoop(): Boolean {
    if (executionHandler == null) {
      return false
    }
    dispatcher!!.eventLoop(defaultDeadlockDetectionTimeout)
    return dispatcher!!.isDone() || executionHandler!!.isDone // Do not wait for all other threads.
  }

  override fun getOutput(): Optional<Payloads> {
    return Optional.ofNullable(executionHandler!!.output)
  }

  override fun cancel(reason: String?) {
    TODO("Implement cancellation")
//    runner!!.cancel(reason)
  }

  override fun close() {
    if (executionHandler != null) {
      //TODO: Validate that cancel is the right operation to call here
      dispatcher!!.cancel()
    }
  }

  override fun query(query: WorkflowQuery): Optional<Payloads> {
    if (WorkflowClient.QUERY_TYPE_REPLAY_ONLY == query.queryType) {
      return Optional.empty()
    }
    if (WorkflowClient.QUERY_TYPE_STACK_TRACE == query.queryType) {
      // stack trace query result should be readable for UI even if user specifies a custom data
      // converter
      TODO("Implement stack trace if possible")
//      return DefaultDataConverter.STANDARD_INSTANCE.toPayloads(runner!!.stackTrace())
    }
    val args = if (query.hasQueryArgs()) Optional.of(query.queryArgs) else Optional.empty()
    TODO("Implement query")
//    return executionHandler!!.handleQuery(query.queryType, args)
  }

  override fun getWorkflowContext(): WorkflowContext? {
    return workflowContext
  }
}