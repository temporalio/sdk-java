package io.temporal.client

import io.temporal.kotlin.TemporalDsl
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.workflow.Functions
import java.lang.reflect.Type
import java.util.concurrent.CompletableFuture

/**
 * Creates client for managing standalone activity executions.
 *
 * @see ActivityClient.newInstance
 */
fun ActivityClient(
  service: WorkflowServiceStubs
): ActivityClient {
  return ActivityClient.newInstance(service)
}

/**
 * Creates client for managing standalone activity executions.
 *
 * @see ActivityClient.newInstance
 */
inline fun ActivityClient(
  service: WorkflowServiceStubs,
  options: @TemporalDsl ActivityClientOptions.Builder.() -> Unit
): ActivityClient {
  return ActivityClient.newInstance(service, ActivityClientOptions(options))
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any> ActivityClient.start(
  activity: Functions.Proc1<I>,
  options: StartActivityOptions
): ActivityHandle<Void> {
  return start(I::class.java, activity, options)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1> ActivityClient.start(
  activity: Functions.Proc2<I, A1>,
  options: StartActivityOptions,
  arg1: A1
): ActivityHandle<Void> {
  return start(I::class.java, activity, options, arg1)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2> ActivityClient.start(
  activity: Functions.Proc3<I, A1, A2>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2
): ActivityHandle<Void> {
  return start(I::class.java, activity, options, arg1, arg2)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, A3> ActivityClient.start(
  activity: Functions.Proc4<I, A1, A2, A3>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3
): ActivityHandle<Void> {
  return start(I::class.java, activity, options, arg1, arg2, arg3)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, A3, A4> ActivityClient.start(
  activity: Functions.Proc5<I, A1, A2, A3, A4>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4
): ActivityHandle<Void> {
  return start(I::class.java, activity, options, arg1, arg2, arg3, arg4)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5> ActivityClient.start(
  activity: Functions.Proc6<I, A1, A2, A3, A4, A5>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5
): ActivityHandle<Void> {
  return start(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, A6> ActivityClient.start(
  activity: Functions.Proc7<I, A1, A2, A3, A4, A5, A6>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5,
  arg6: A6
): ActivityHandle<Void> {
  return start(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, R> ActivityClient.start(
  activity: Functions.Func1<I, R>,
  options: StartActivityOptions
): ActivityHandle<R> {
  return start(I::class.java, activity, options)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, R> ActivityClient.start(
  activity: Functions.Func2<I, A1, R>,
  options: StartActivityOptions,
  arg1: A1
): ActivityHandle<R> {
  return start(I::class.java, activity, options, arg1)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, R> ActivityClient.start(
  activity: Functions.Func3<I, A1, A2, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2
): ActivityHandle<R> {
  return start(I::class.java, activity, options, arg1, arg2)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, A3, R> ActivityClient.start(
  activity: Functions.Func4<I, A1, A2, A3, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3
): ActivityHandle<R> {
  return start(I::class.java, activity, options, arg1, arg2, arg3)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, A3, A4, R> ActivityClient.start(
  activity: Functions.Func5<I, A1, A2, A3, A4, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4
): ActivityHandle<R> {
  return start(I::class.java, activity, options, arg1, arg2, arg3, arg4)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, R> ActivityClient.start(
  activity: Functions.Func6<I, A1, A2, A3, A4, A5, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5
): ActivityHandle<R> {
  return start(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5)
}

/**
 * Starts a standalone activity using a typed interface and an unbound method reference.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.start
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, A6, R> ActivityClient.start(
  activity: Functions.Func7<I, A1, A2, A3, A4, A5, A6, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5,
  arg6: A6
): ActivityHandle<R> {
  return start(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
}

/**
 * Starts a standalone activity by type name and returns a typed handle.
 *
 * @param R activity result type.
 * @see ActivityClient.start
 */
inline fun <reified R : Any> ActivityClient.start(
  activityType: String,
  resultType: Type,
  options: StartActivityOptions,
  vararg args: Any?
): ActivityHandle<R> {
  return start(activityType, R::class.java, resultType, options, *args)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any> ActivityClient.execute(
  activity: Functions.Proc1<I>,
  options: StartActivityOptions
) {
  execute(I::class.java, activity, options)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1> ActivityClient.execute(
  activity: Functions.Proc2<I, A1>,
  options: StartActivityOptions,
  arg1: A1
) {
  execute(I::class.java, activity, options, arg1)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2> ActivityClient.execute(
  activity: Functions.Proc3<I, A1, A2>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2
) {
  execute(I::class.java, activity, options, arg1, arg2)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, A3> ActivityClient.execute(
  activity: Functions.Proc4<I, A1, A2, A3>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3
) {
  execute(I::class.java, activity, options, arg1, arg2, arg3)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, A3, A4> ActivityClient.execute(
  activity: Functions.Proc5<I, A1, A2, A3, A4>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4
) {
  execute(I::class.java, activity, options, arg1, arg2, arg3, arg4)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5> ActivityClient.execute(
  activity: Functions.Proc6<I, A1, A2, A3, A4, A5>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5
) {
  execute(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, A6> ActivityClient.execute(
  activity: Functions.Proc7<I, A1, A2, A3, A4, A5, A6>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5,
  arg6: A6
) {
  execute(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, R> ActivityClient.execute(
  activity: Functions.Func1<I, R>,
  options: StartActivityOptions
): R {
  return execute(I::class.java, activity, options)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, R> ActivityClient.execute(
  activity: Functions.Func2<I, A1, R>,
  options: StartActivityOptions,
  arg1: A1
): R {
  return execute(I::class.java, activity, options, arg1)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, R> ActivityClient.execute(
  activity: Functions.Func3<I, A1, A2, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2
): R {
  return execute(I::class.java, activity, options, arg1, arg2)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, A3, R> ActivityClient.execute(
  activity: Functions.Func4<I, A1, A2, A3, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3
): R {
  return execute(I::class.java, activity, options, arg1, arg2, arg3)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, A3, A4, R> ActivityClient.execute(
  activity: Functions.Func5<I, A1, A2, A3, A4, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4
): R {
  return execute(I::class.java, activity, options, arg1, arg2, arg3, arg4)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, R> ActivityClient.execute(
  activity: Functions.Func6<I, A1, A2, A3, A4, A5, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5
): R {
  return execute(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5)
}

/**
 * Starts a standalone activity using a typed interface and blocks until it completes.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.execute
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, A6, R> ActivityClient.execute(
  activity: Functions.Func7<I, A1, A2, A3, A4, A5, A6, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5,
  arg6: A6
): R {
  return execute(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
}

/**
 * Starts a standalone activity by type name and blocks until it completes.
 *
 * @param R activity result type.
 * @see ActivityClient.execute
 */
inline fun <reified R : Any> ActivityClient.execute(
  activityType: String,
  resultType: Type,
  options: StartActivityOptions,
  vararg args: Any?
): R {
  return execute(activityType, R::class.java, resultType, options, *args)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any> ActivityClient.executeAsync(
  activity: Functions.Proc1<I>,
  options: StartActivityOptions
): CompletableFuture<Void> {
  return executeAsync(I::class.java, activity, options)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1> ActivityClient.executeAsync(
  activity: Functions.Proc2<I, A1>,
  options: StartActivityOptions,
  arg1: A1
): CompletableFuture<Void> {
  return executeAsync(I::class.java, activity, options, arg1)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2> ActivityClient.executeAsync(
  activity: Functions.Proc3<I, A1, A2>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2
): CompletableFuture<Void> {
  return executeAsync(I::class.java, activity, options, arg1, arg2)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, A3> ActivityClient.executeAsync(
  activity: Functions.Proc4<I, A1, A2, A3>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3
): CompletableFuture<Void> {
  return executeAsync(I::class.java, activity, options, arg1, arg2, arg3)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, A3, A4> ActivityClient.executeAsync(
  activity: Functions.Proc5<I, A1, A2, A3, A4>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4
): CompletableFuture<Void> {
  return executeAsync(I::class.java, activity, options, arg1, arg2, arg3, arg4)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5> ActivityClient.executeAsync(
  activity: Functions.Proc6<I, A1, A2, A3, A4, A5>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5
): CompletableFuture<Void> {
  return executeAsync(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, A6> ActivityClient.executeAsync(
  activity: Functions.Proc7<I, A1, A2, A3, A4, A5, A6>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5,
  arg6: A6
): CompletableFuture<Void> {
  return executeAsync(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, R> ActivityClient.executeAsync(
  activity: Functions.Func1<I, R>,
  options: StartActivityOptions
): CompletableFuture<R> {
  return executeAsync(I::class.java, activity, options)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, R> ActivityClient.executeAsync(
  activity: Functions.Func2<I, A1, R>,
  options: StartActivityOptions,
  arg1: A1
): CompletableFuture<R> {
  return executeAsync(I::class.java, activity, options, arg1)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, R> ActivityClient.executeAsync(
  activity: Functions.Func3<I, A1, A2, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2
): CompletableFuture<R> {
  return executeAsync(I::class.java, activity, options, arg1, arg2)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, A3, R> ActivityClient.executeAsync(
  activity: Functions.Func4<I, A1, A2, A3, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3
): CompletableFuture<R> {
  return executeAsync(I::class.java, activity, options, arg1, arg2, arg3)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, A3, A4, R> ActivityClient.executeAsync(
  activity: Functions.Func5<I, A1, A2, A3, A4, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4
): CompletableFuture<R> {
  return executeAsync(I::class.java, activity, options, arg1, arg2, arg3, arg4)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, R> ActivityClient.executeAsync(
  activity: Functions.Func6<I, A1, A2, A3, A4, A5, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5
): CompletableFuture<R> {
  return executeAsync(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5)
}

/**
 * Starts a standalone activity using a typed interface and returns a future.
 *
 * @param I interface that given activity implements.
 * @see ActivityClient.executeAsync
 */
inline fun <reified I : Any, A1, A2, A3, A4, A5, A6, R> ActivityClient.executeAsync(
  activity: Functions.Func7<I, A1, A2, A3, A4, A5, A6, R>,
  options: StartActivityOptions,
  arg1: A1,
  arg2: A2,
  arg3: A3,
  arg4: A4,
  arg5: A5,
  arg6: A6
): CompletableFuture<R> {
  return executeAsync(I::class.java, activity, options, arg1, arg2, arg3, arg4, arg5, arg6)
}

/**
 * Starts a standalone activity by type name and returns a future.
 *
 * @param R activity result type.
 * @see ActivityClient.executeAsync
 */
inline fun <reified R : Any> ActivityClient.executeAsync(
  activityType: String,
  resultType: Type,
  options: StartActivityOptions,
  vararg args: Any?
): CompletableFuture<R> {
  return executeAsync(activityType, R::class.java, resultType, options, *args)
}

/**
 * Returns a typed handle to an existing standalone activity execution.
 *
 * @param R activity result type.
 * @see ActivityClient.getHandle
 */
inline fun <reified R : Any> ActivityClient.getHandle(
  activityId: String
): ActivityHandle<R> {
  return getHandle(activityId, null, R::class.java)
}

/**
 * Returns a typed handle to an existing standalone activity execution.
 *
 * @param R activity result type.
 * @see ActivityClient.getHandle
 */
inline fun <reified R : Any> ActivityClient.getHandle(
  activityId: String,
  resultType: Type?
): ActivityHandle<R> {
  return getHandle(activityId, null, R::class.java, resultType)
}

/**
 * Returns a typed handle to an existing standalone activity execution.
 *
 * @param R activity result type.
 * @see ActivityClient.getHandle
 */
inline fun <reified R : Any> ActivityClient.getHandle(
  activityId: String,
  activityRunId: String?,
  resultType: Type?
): ActivityHandle<R> {
  return getHandle(activityId, activityRunId, R::class.java, resultType)
}
