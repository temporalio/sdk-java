package io.temporal.worker

import io.temporal.activity.ActivityInterface
import io.temporal.api.worker.v1.EnvironmentInfo
import io.temporal.api.worker.v1.EnvironmentInfo.Runtime.RuntimeType
import io.temporal.client.WorkflowClientOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration

class KotlinWorkerHeartbeatTest {
  private lateinit var testEnvironment: TestWorkflowEnvironment

  private val jvmRuntime = EnvironmentInfo.Runtime.newBuilder()
    .setType(RuntimeType.RUNTIME_TYPE_JVM)
    .setVersion("test-jvm-version")
    .build()
  private val environment = EnvironmentInfo.newBuilder()
    .addRuntimes(jvmRuntime)
    .build()

  @Before
  fun setUp() {
    // Exercise callbacks directly without a background heartbeat accepting the environment.
    testEnvironment = TestWorkflowEnvironment.newInstance(
      TestEnvironmentOptions.newBuilder()
        .setWorkflowClientOptions(
          WorkflowClientOptions.newBuilder()
            .setWorkerHeartbeatInterval(Duration.ofSeconds(-1))
            .build()
        )
        .build()
    )
  }

  @After
  fun tearDown() {
    testEnvironment.close()
  }

  @Test
  fun kotlinWorkflowRegistrationReportsKotlin() {
    val worker = testEnvironment.newWorker("kotlin-workflow")
    worker.registerWorkflowImplementationTypes(KotlinWorkflowImpl::class.java)
    testEnvironment.start()

    assertKotlinEnvironment(worker.buildHeartbeatCallback("group", environment).get().environment)
  }

  @Test
  fun kotlinWorkflowRegistrationWithOptionsReportsKotlin() {
    val worker = testEnvironment.newWorker("kotlin-workflow-options")
    worker.registerWorkflowImplementationTypes(
      WorkflowImplementationOptions.newBuilder().build(),
      KotlinWorkflowImpl::class.java
    )
    testEnvironment.start()

    assertKotlinEnvironment(worker.buildHeartbeatCallback("group", environment).get().environment)
  }

  @Test
  fun kotlinActivityRegistrationReportsKotlinForLocalOnlyWorker() {
    val worker = testEnvironment.newWorker(
      "kotlin-local-activity",
      WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build()
    )
    worker.registerActivitiesImplementations(KotlinActivityImpl())
    testEnvironment.start()

    assertKotlinEnvironment(worker.buildHeartbeatCallback("group", environment).get().environment)
  }

  @Test
  fun kotlinDetectionIsPerWorkerNotPerClasspathOrFactory() {
    val kotlinWorker = testEnvironment.newWorker("kotlin")
    kotlinWorker.registerWorkflowImplementationTypes(KotlinWorkflowImpl::class.java)
    val javaWorker = testEnvironment.newWorker("java")
    javaWorker.registerWorkflowImplementationTypes(JavaHeartbeatImplementations.JavaWorkflowImpl::class.java)
    javaWorker.registerActivitiesImplementations(JavaHeartbeatImplementations.JavaActivityImpl())
    val unregisteredWorker = testEnvironment.newWorker("unregistered")
    testEnvironment.start()

    assertKotlinEnvironment(kotlinWorker.buildHeartbeatCallback("group", environment).get().environment)
    assertEquals(environment, javaWorker.buildHeartbeatCallback("group", environment).get().environment)
    assertEquals(environment, unregisteredWorker.buildHeartbeatCallback("group", environment).get().environment)
  }

  @Test
  fun environmentIsRetriedUntilHeartbeatAccepted() {
    val worker = testEnvironment.newWorker("retry")
    worker.registerWorkflowImplementationTypes(KotlinWorkflowImpl::class.java)
    testEnvironment.start()
    val heartbeat = worker.buildHeartbeatCallback("group", environment)

    val first = heartbeat.get()
    assertTrue(first.hasEnvironment())
    assertKotlinEnvironment(first.environment)
    repeat(3) {
      val retry = heartbeat.get()
      assertTrue(retry.hasEnvironment())
      assertEquals(first.environment, retry.environment)
    }

    worker.onHeartbeatAccepted()
    repeat(3) {
      assertFalse(heartbeat.get().hasEnvironment())
    }
  }

  @Test
  fun nullEnvironmentOptsOutEvenForKotlinImplementations() {
    val worker = testEnvironment.newWorker("optout")
    worker.registerWorkflowImplementationTypes(KotlinWorkflowImpl::class.java)
    worker.registerActivitiesImplementations(KotlinActivityImpl())
    testEnvironment.start()
    val heartbeat = worker.buildHeartbeatCallback("group", null)

    repeat(3) {
      assertFalse(heartbeat.get().hasEnvironment())
    }
    worker.onHeartbeatAccepted()
    assertFalse(heartbeat.get().hasEnvironment())
  }

  private fun assertKotlinEnvironment(actual: EnvironmentInfo) {
    assertEquals(2, actual.runtimesCount)
    assertEquals(jvmRuntime, actual.runtimesList.single { it.type == RuntimeType.RUNTIME_TYPE_JVM })
    val kotlinRuntime = actual.runtimesList.single { it.type == RuntimeType.RUNTIME_TYPE_KOTLIN }
    assertEquals(KotlinVersion.CURRENT.toString(), kotlinRuntime.version)
    // Augmenting the heartbeat must not mutate the caller's environment.
    assertEquals(listOf(jvmRuntime), environment.runtimesList)
  }

  @WorkflowInterface
  interface KotlinWorkflow {
    @WorkflowMethod
    fun execute()
  }

  class KotlinWorkflowImpl : KotlinWorkflow {
    override fun execute() {}
  }

  @ActivityInterface
  interface KotlinActivity {
    fun executeActivity()
  }

  class KotlinActivityImpl : KotlinActivity {
    override fun executeActivity() {}
  }
}
