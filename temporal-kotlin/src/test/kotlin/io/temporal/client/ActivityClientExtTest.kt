package io.temporal.client

import com.google.common.reflect.TypeToken
import io.temporal.workflow.Functions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Type
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.stream.Stream

class ActivityClientExtTest {

  private val options = StartActivityOptions.newBuilder()
    .setId("activity-id")
    .setTaskQueue("task-queue")
    .setStartToCloseTimeout(Duration.ofSeconds(10))
    .build()

  @Test
  fun `typed start extension should pass reified activity interface`() {
    val recordingClient = RecordingActivityClient()
    val activity = Functions.Proc2<TestActivity, String> { _, _ -> }

    val handle = recordingClient.client.start<TestActivity, String>(activity, options, "arg1")

    val call = recordingClient.lastCall()
    assertEquals("start", call.methodName)
    assertEquals(TestActivity::class.java, call.args[0])
    assertSame(activity, call.args[1])
    assertSame(options, call.args[2])
    assertEquals("arg1", call.args[3])
    assertSame(recordingClient.handle, handle)
  }

  @Test
  fun `typed start extension should pass reified activity interface for result activity`() {
    val recordingClient = RecordingActivityClient()
    val activity = Functions.Func1<TestActivity, String> { "result" }

    val handle = recordingClient.client.start<TestActivity, String>(activity, options)

    val call = recordingClient.lastCall()
    assertEquals("start", call.methodName)
    assertEquals(TestActivity::class.java, call.args[0])
    assertSame(activity, call.args[1])
    assertSame(options, call.args[2])
    assertSame(recordingClient.handle, handle)
  }

  @Test
  fun `generic start extension should pass reified result class and result type`() {
    val recordingClient = RecordingActivityClient()
    val resultType = object : TypeToken<List<String>>() {}.type

    val handle = recordingClient.client.start<List<String>>(
      "ActivityType",
      resultType,
      options,
      "arg1",
      "arg2"
    )

    val call = recordingClient.lastCall()
    assertEquals("start", call.methodName)
    assertEquals("ActivityType", call.args[0])
    assertEquals(List::class.java, call.args[1])
    assertSame(resultType, call.args[2])
    assertSame(options, call.args[3])
    assertEquals(listOf("arg1", "arg2"), (call.args[4] as Array<*>).toList())
    assertSame(recordingClient.handle, handle)
  }

  @Test
  fun `typed execute extension should pass reified activity interface`() {
    val recordingClient = RecordingActivityClient()
    val activity = Functions.Func2<TestActivity, String, String> { _, arg -> arg }

    val result = recordingClient.client.execute<TestActivity, String, String>(
      activity,
      options,
      "arg1"
    )

    val call = recordingClient.lastCall()
    assertEquals("execute", call.methodName)
    assertEquals(TestActivity::class.java, call.args[0])
    assertSame(activity, call.args[1])
    assertSame(options, call.args[2])
    assertEquals("arg1", call.args[3])
    assertEquals("execute-result", result)
  }

  @Test
  fun `generic execute extension should pass reified result class and result type`() {
    val recordingClient = RecordingActivityClient()
    val resultType = object : TypeToken<List<String>>() {}.type

    val result = recordingClient.client.execute<List<String>>(
      "ActivityType",
      resultType,
      options,
      "arg1"
    )

    val call = recordingClient.lastCall()
    assertEquals("execute", call.methodName)
    assertEquals("ActivityType", call.args[0])
    assertEquals(List::class.java, call.args[1])
    assertSame(resultType, call.args[2])
    assertSame(options, call.args[3])
    assertEquals(listOf("arg1"), (call.args[4] as Array<*>).toList())
    assertEquals(listOf("execute-result"), result)
  }

  @Test
  fun `typed executeAsync extension should pass reified activity interface`() {
    val recordingClient = RecordingActivityClient()
    val activity = Functions.Func1<TestActivity, String> { "result" }

    val result = recordingClient.client.executeAsync<TestActivity, String>(activity, options)

    val call = recordingClient.lastCall()
    assertEquals("executeAsync", call.methodName)
    assertEquals(TestActivity::class.java, call.args[0])
    assertSame(activity, call.args[1])
    assertSame(options, call.args[2])
    assertSame(recordingClient.future, result)
  }

  @Test
  fun `generic executeAsync extension should pass reified result class and result type`() {
    val recordingClient = RecordingActivityClient()
    val resultType = object : TypeToken<List<String>>() {}.type

    val result = recordingClient.client.executeAsync<List<String>>(
      "ActivityType",
      resultType,
      options,
      "arg1"
    )

    val call = recordingClient.lastCall()
    assertEquals("executeAsync", call.methodName)
    assertEquals("ActivityType", call.args[0])
    assertEquals(List::class.java, call.args[1])
    assertSame(resultType, call.args[2])
    assertSame(options, call.args[3])
    assertEquals(listOf("arg1"), (call.args[4] as Array<*>).toList())
    assertSame(recordingClient.future, result)
  }

  @Test
  fun `typed getHandle extension should pass reified result class`() {
    val recordingClient = RecordingActivityClient()

    val handle = recordingClient.client.getHandle<String>("activity-id")

    val call = recordingClient.lastCall()
    assertEquals("getHandle", call.methodName)
    assertEquals("activity-id", call.args[0])
    assertEquals(null, call.args[1])
    assertEquals(String::class.java, call.args[2])
    assertSame(recordingClient.handle, handle)
  }

  @Test
  fun `generic getHandle extension should pass reified result class and result type`() {
    val recordingClient = RecordingActivityClient()
    val resultType = object : TypeToken<List<String>>() {}.type

    val handle = recordingClient.client.getHandle<List<String>>(
      "activity-id",
      "run-id",
      resultType
    )

    val call = recordingClient.lastCall()
    assertEquals("getHandle", call.methodName)
    assertEquals("activity-id", call.args[0])
    assertEquals("run-id", call.args[1])
    assertEquals(List::class.java, call.args[2])
    assertSame(resultType, call.args[3])
    assertSame(recordingClient.handle, handle)
  }

  interface TestActivity {
    fun execute(arg: String): String
  }

  private data class RecordedCall(
    val methodName: String,
    val args: List<Any?>
  )

  @Suppress("UNCHECKED_CAST")
  private class RecordingActivityClient : ActivityClient {
    val calls = mutableListOf<RecordedCall>()
    val handle: ActivityHandle<Any> = RecordingActivityHandle()
    val future: CompletableFuture<Any> = CompletableFuture.completedFuture("async-result")
    val client: ActivityClient = this

    fun lastCall(): RecordedCall = calls.last()

    private fun record(methodName: String, vararg args: Any?) {
      calls += RecordedCall(methodName, args.toList())
    }

    override fun <I> start(
      activityInterface: Class<I>,
      activity: Functions.Proc1<I>,
      options: StartActivityOptions
    ): ActivityHandle<Void> {
      record("start", activityInterface, activity, options)
      return castHandle()
    }

    override fun <I, A1> start(
      activityInterface: Class<I>,
      activity: Functions.Proc2<I, A1>,
      options: StartActivityOptions,
      arg1: A1
    ): ActivityHandle<Void> {
      record("start", activityInterface, activity, options, arg1)
      return castHandle()
    }

    override fun <I, A1, A2> start(
      activityInterface: Class<I>,
      activity: Functions.Proc3<I, A1, A2>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2
    ): ActivityHandle<Void> {
      record("start", activityInterface, activity, options, arg1, arg2)
      return castHandle()
    }

    override fun <I, A1, A2, A3> start(
      activityInterface: Class<I>,
      activity: Functions.Proc4<I, A1, A2, A3>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2,
      arg3: A3
    ): ActivityHandle<Void> {
      record("start", activityInterface, activity, options, arg1, arg2, arg3)
      return castHandle()
    }

    override fun <I, A1, A2, A3, A4> start(
      activityInterface: Class<I>,
      activity: Functions.Proc5<I, A1, A2, A3, A4>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2,
      arg3: A3,
      arg4: A4
    ): ActivityHandle<Void> {
      record("start", activityInterface, activity, options, arg1, arg2, arg3, arg4)
      return castHandle()
    }

    override fun <I, A1, A2, A3, A4, A5> start(
      activityInterface: Class<I>,
      activity: Functions.Proc6<I, A1, A2, A3, A4, A5>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2,
      arg3: A3,
      arg4: A4,
      arg5: A5
    ): ActivityHandle<Void> {
      record("start", activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5)
      return castHandle()
    }

    override fun <I, A1, A2, A3, A4, A5, A6> start(
      activityInterface: Class<I>,
      activity: Functions.Proc7<I, A1, A2, A3, A4, A5, A6>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2,
      arg3: A3,
      arg4: A4,
      arg5: A5,
      arg6: A6
    ): ActivityHandle<Void> {
      record("start", activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
      return castHandle()
    }

    override fun <I, R> start(
      activityInterface: Class<I>,
      activity: Functions.Func1<I, R>,
      options: StartActivityOptions
    ): ActivityHandle<R> {
      record("start", activityInterface, activity, options)
      return castHandle()
    }

    override fun <I, A1, R> start(
      activityInterface: Class<I>,
      activity: Functions.Func2<I, A1, R>,
      options: StartActivityOptions,
      arg1: A1
    ): ActivityHandle<R> {
      record("start", activityInterface, activity, options, arg1)
      return castHandle()
    }

    override fun <I, A1, A2, R> start(
      activityInterface: Class<I>,
      activity: Functions.Func3<I, A1, A2, R>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2
    ): ActivityHandle<R> {
      record("start", activityInterface, activity, options, arg1, arg2)
      return castHandle()
    }

    override fun <I, A1, A2, A3, R> start(
      activityInterface: Class<I>,
      activity: Functions.Func4<I, A1, A2, A3, R>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2,
      arg3: A3
    ): ActivityHandle<R> {
      record("start", activityInterface, activity, options, arg1, arg2, arg3)
      return castHandle()
    }

    override fun <I, A1, A2, A3, A4, R> start(
      activityInterface: Class<I>,
      activity: Functions.Func5<I, A1, A2, A3, A4, R>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2,
      arg3: A3,
      arg4: A4
    ): ActivityHandle<R> {
      record("start", activityInterface, activity, options, arg1, arg2, arg3, arg4)
      return castHandle()
    }

    override fun <I, A1, A2, A3, A4, A5, R> start(
      activityInterface: Class<I>,
      activity: Functions.Func6<I, A1, A2, A3, A4, A5, R>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2,
      arg3: A3,
      arg4: A4,
      arg5: A5
    ): ActivityHandle<R> {
      record("start", activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5)
      return castHandle()
    }

    override fun <I, A1, A2, A3, A4, A5, A6, R> start(
      activityInterface: Class<I>,
      activity: Functions.Func7<I, A1, A2, A3, A4, A5, A6, R>,
      options: StartActivityOptions,
      arg1: A1,
      arg2: A2,
      arg3: A3,
      arg4: A4,
      arg5: A5,
      arg6: A6
    ): ActivityHandle<R> {
      record("start", activityInterface, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
      return castHandle()
    }

    override fun start(
      activityType: String,
      options: StartActivityOptions,
      vararg args: Any?
    ): UntypedActivityHandle {
      record("start", activityType, options, args)
      return handle
    }

    override fun <R> start(
      activityType: String,
      resultClass: Class<R>,
      options: StartActivityOptions,
      vararg args: Any?
    ): ActivityHandle<R> {
      record("start", activityType, resultClass, options, args)
      return castHandle()
    }

    override fun <R> start(
      activityType: String,
      resultClass: Class<R>,
      resultType: Type,
      options: StartActivityOptions,
      vararg args: Any?
    ): ActivityHandle<R> {
      record("start", activityType, resultClass, resultType, options, args)
      return castHandle()
    }

    override fun <I, A1, R> execute(
      activityInterface: Class<I>,
      activity: Functions.Func2<I, A1, R>,
      options: StartActivityOptions,
      arg1: A1
    ): R {
      record("execute", activityInterface, activity, options, arg1)
      return "execute-result" as R
    }

    override fun <R> execute(
      activityType: String,
      resultClass: Class<R>,
      resultType: Type,
      options: StartActivityOptions,
      vararg args: Any?
    ): R {
      record("execute", activityType, resultClass, resultType, options, args)
      return listOf("execute-result") as R
    }

    override fun <I, R> executeAsync(
      activityInterface: Class<I>,
      activity: Functions.Func1<I, R>,
      options: StartActivityOptions
    ): CompletableFuture<R> {
      record("executeAsync", activityInterface, activity, options)
      return future as CompletableFuture<R>
    }

    override fun <R> executeAsync(
      activityType: String,
      resultClass: Class<R>,
      resultType: Type,
      options: StartActivityOptions,
      vararg args: Any?
    ): CompletableFuture<R> {
      record("executeAsync", activityType, resultClass, resultType, options, args)
      return future as CompletableFuture<R>
    }

    override fun getHandle(
      activityId: String,
      activityRunId: String?
    ): UntypedActivityHandle {
      record("getHandle", activityId, activityRunId)
      return handle
    }

    override fun <R> getHandle(
      activityId: String,
      activityRunId: String?,
      resultClass: Class<R>
    ): ActivityHandle<R> {
      record("getHandle", activityId, activityRunId, resultClass)
      return castHandle()
    }

    override fun <R> getHandle(
      activityId: String,
      activityRunId: String?,
      resultClass: Class<R>,
      resultType: Type?
    ): ActivityHandle<R> {
      record("getHandle", activityId, activityRunId, resultClass, resultType)
      return castHandle()
    }

    override fun listExecutions(query: String): Stream<ActivityExecutionMetadata> {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun countExecutions(query: String): ActivityExecutionCount {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun newActivityCompletionClient(): ActivityCompletionClient {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    private fun <R> castHandle(): ActivityHandle<R> {
      return handle as ActivityHandle<R>
    }
  }

  private class RecordingActivityHandle : ActivityHandle<Any> {
    override fun getActivityId(): String = "activity-id"

    override fun getActivityRunId(): String = "activity-run-id"

    override fun getResult(): Any {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun getResult(timeout: Long, unit: TimeUnit): Any {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun getResultAsync(): CompletableFuture<Any> {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun getResultAsync(timeout: Long, unit: TimeUnit): CompletableFuture<Any> {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun <R> getResult(resultClass: Class<R>): R {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun <R> getResult(resultClass: Class<R>, resultType: Type?): R {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    @Throws(TimeoutException::class)
    override fun <R> getResult(timeout: Long, unit: TimeUnit, resultClass: Class<R>): R {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    @Throws(TimeoutException::class)
    override fun <R> getResult(
      timeout: Long,
      unit: TimeUnit,
      resultClass: Class<R>,
      resultType: Type?
    ): R {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun <R> getResultAsync(resultClass: Class<R>): CompletableFuture<R> {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun <R> getResultAsync(resultClass: Class<R>, resultType: Type?): CompletableFuture<R> {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun <R> getResultAsync(
      timeout: Long,
      unit: TimeUnit,
      resultClass: Class<R>
    ): CompletableFuture<R> {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun <R> getResultAsync(
      timeout: Long,
      unit: TimeUnit,
      resultClass: Class<R>,
      resultType: Type?
    ): CompletableFuture<R> {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun describe(): ActivityExecutionDescription {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun cancel() {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun cancel(reason: String?) {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun terminate() {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }

    override fun terminate(reason: String?) {
      throw UnsupportedOperationException("Not needed by ActivityClient extension tests.")
    }
  }
}
