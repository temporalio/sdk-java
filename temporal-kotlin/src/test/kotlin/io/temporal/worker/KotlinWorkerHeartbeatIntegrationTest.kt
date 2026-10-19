package io.temporal.worker

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.worker.v1.EnvironmentInfo.Runtime.RuntimeType
import io.temporal.api.worker.v1.WorkerHeartbeat
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest
import io.temporal.api.workflowservice.v1.DescribeWorkerRequest
import io.temporal.api.workflowservice.v1.ListWorkersRequest
import io.temporal.api.workflowservice.v1.RecordWorkerHeartbeatRequest
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.testing.internal.SDKTestWorkflowRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class KotlinWorkerHeartbeatIntegrationTest {
  private val acceptedEnvironments = ConcurrentHashMap<String, WorkerHeartbeat>()
  private val workersWithLaterHeartbeats = ConcurrentHashMap.newKeySet<String>()
  private val heartbeatInterceptor = object : ClientInterceptor {
    override fun <ReqT, RespT> interceptCall(
      method: MethodDescriptor<ReqT, RespT>,
      callOptions: CallOptions,
      next: Channel
    ): ClientCall<ReqT, RespT> {
      val call = next.newCall(method, callOptions)
      if (method != WorkflowServiceGrpc.getRecordWorkerHeartbeatMethod()) {
        return call
      }
      return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
        @Volatile
        private var request: RecordWorkerHeartbeatRequest? = null

        override fun sendMessage(message: ReqT) {
          request = message as RecordWorkerHeartbeatRequest
          super.sendMessage(message)
        }

        override fun start(responseListener: Listener<RespT>, headers: Metadata) {
          super.start(
            object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
              override fun onClose(status: Status, trailers: Metadata) {
                if (status.isOk) {
                  request?.workerHeartbeatList?.forEach {
                    if (it.hasEnvironment()) {
                      acceptedEnvironments.putIfAbsent(it.workerInstanceKey, it)
                    } else {
                      workersWithLaterHeartbeats.add(it.workerInstanceKey)
                    }
                  }
                }
                super.onClose(status, trailers)
              }
            },
            headers
          )
        }
      }
    }
  }

  @Rule
  @JvmField
  val testWorkflowRule = SDKTestWorkflowRule.newBuilder()
    .setTestTimeoutSeconds(45)
    .setWorkflowServiceStubsOptions(
      WorkflowServiceStubsOptions.newBuilder()
        .setGrpcClientInterceptors(listOf(heartbeatInterceptor))
        .build()
    )
    .setWorkflowClientOptions(
      WorkflowClientOptions.newBuilder()
        .setWorkerHeartbeatInterval(Duration.ofSeconds(1))
        .build()
    )
    .setWorkflowTypes(KotlinWorkerHeartbeatTest.KotlinWorkflowImpl::class.java)
    .setDoNotStart(true)
    .build()

  private val client get() = testWorkflowRule.workflowClient
  private val service get() = client.workflowServiceStubs.blockingStub()
  private val namespace get() = client.options.namespace

  @Before
  fun checkServerSupportsHeartbeats() {
    assumeTrue(
      "Requires real server with worker heartbeat support",
      SDKTestWorkflowRule.useExternalService
    )
    assumeTrue(
      "Server does not support worker heartbeats",
      service.describeNamespace(
        DescribeNamespaceRequest.newBuilder().setNamespace(namespace).build()
      ).namespaceInfo.capabilities.workerHeartbeats
    )
  }

  @Test
  fun reportsRuntimeVersionsForKotlinWorkflowAndActivityButNotJava() {
    val workflowQueue = testWorkflowRule.taskQueue
    val activityQueue = "$workflowQueue-kotlin-activity"
    val javaQueue = "$workflowQueue-java"
    val environment = testWorkflowRule.testEnvironment
    environment.newWorker(activityQueue)
      .registerActivitiesImplementations(KotlinWorkerHeartbeatTest.KotlinActivityImpl())
    val javaWorker = environment.newWorker(javaQueue)
    javaWorker.registerWorkflowImplementationTypes(JavaHeartbeatImplementations.JavaWorkflowImpl::class.java)
    javaWorker.registerActivitiesImplementations(JavaHeartbeatImplementations.JavaActivityImpl())
    environment.start()

    val pendingQueues = linkedMapOf(
      workflowQueue to true,
      activityQueue to true,
      javaQueue to false
    )
    assertEventually(Duration.ofSeconds(25)) {
      for ((taskQueue, kotlin) in pendingQueues.toMap()) {
        assertServerRuntimes(taskQueue, kotlin)
        pendingQueues.remove(taskQueue)
      }
    }
  }

  private fun assertServerRuntimes(taskQueue: String, kotlin: Boolean) {
    val workers = listWorkersForQueue(taskQueue)
    assertFalse("No workers found via ListWorkers for queue: $taskQueue", workers.isEmpty())
    val listed = workers.single()
    assertFalse("Worker instance key should not be empty", listed.workerInstanceKey.isEmpty())
    val described = describeWorker(listed.workerInstanceKey)
    assertNotNull("DescribeWorker should return a stored heartbeat", described)
    assertEquals(taskQueue, described!!.taskQueue)
    assertEquals(listed.workerInstanceKey, described.workerInstanceKey)
    assertTrue(
      "Waiting for a later accepted heartbeat without environment for queue: $taskQueue",
      workersWithLaterHeartbeats.contains(listed.workerInstanceKey)
    )
    // Later heartbeats omit the one-shot environment, so inspect a successfully accepted RPC.
    val accepted = acceptedEnvironments[listed.workerInstanceKey]
    assertNotNull("No accepted heartbeat with environment for queue: $taskQueue", accepted)
    assertRuntimes(accepted!!, kotlin)
  }

  private fun assertRuntimes(heartbeat: WorkerHeartbeat, kotlin: Boolean) {
    val expected = mutableMapOf(RuntimeType.RUNTIME_TYPE_JVM to System.getProperty("java.version"))
    if (kotlin) {
      expected[RuntimeType.RUNTIME_TYPE_KOTLIN] = KotlinVersion.CURRENT.toString()
    }
    val runtimes = heartbeat.environment.runtimesList
    assertEquals("Runtime entries should not be duplicated", expected.size, runtimes.size)
    assertEquals(expected, runtimes.associate { it.type to it.version })
  }

  // WorkersInfo is deprecated, but its replacement is not yet populated by the server.
  @Suppress("DEPRECATION")
  private fun listWorkersForQueue(taskQueue: String): List<WorkerHeartbeat> {
    try {
      return service.listWorkers(
        ListWorkersRequest.newBuilder()
          .setNamespace(namespace)
          .setQuery("TaskQueue = \"$taskQueue\"")
          .setPageSize(200)
          .build()
      ).workersInfoList.map { it.workerHeartbeat }
    } catch (e: StatusRuntimeException) {
      if (e.status.code == Status.Code.RESOURCE_EXHAUSTED) {
        return emptyList()
      }
      throw e
    }
  }

  private fun describeWorker(workerInstanceKey: String): WorkerHeartbeat? {
    try {
      return service.describeWorker(
        DescribeWorkerRequest.newBuilder()
          .setNamespace(namespace)
          .setWorkerInstanceKey(workerInstanceKey)
          .build()
      ).workerInfo.workerHeartbeat
    } catch (e: StatusRuntimeException) {
      if (e.status.code == Status.Code.NOT_FOUND || e.status.code == Status.Code.RESOURCE_EXHAUSTED) {
        return null
      }
      throw e
    }
  }

  private fun assertEventually(timeout: Duration, assertion: () -> Unit) {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
      try {
        assertion()
        return
      } catch (e: AssertionError) {
        if (System.nanoTime() >= deadline) {
          throw e
        }
        Thread.sleep(100)
      }
    }
  }
}
