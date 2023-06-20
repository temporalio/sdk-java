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

package io.temporal.internal.async

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.*
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Suppress("UNUSED_PARAMETER")
@OptIn(InternalCoroutinesApi::class)
class TemporalCoroutineDispatcher : CoroutineDispatcher(), Delay {

  private val queue: java.util.Queue<Runnable> = LinkedList()
  private val callbackQueue: Queue<Runnable> = LinkedList()
  private val delayQueue: DelayQueue<DelayedContinuation> = DelayQueue()

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    queue.add(block)
  }

  fun dispatchCallback(context: CoroutineContext, block: Runnable) {
    callbackQueue.add(block)
  }

  // TODO: deadlock detector
  fun eventLoop(defaultDeadlockDetectionTimeout: Long): Boolean {
//        println("eventLoop begin")
    if (isDone()) {
      println("eventLoop completed")
      return false
    }

    while (callbackQueue.isNotEmpty()) {
      val block = callbackQueue.poll()
      block.run()
    }

    while (queue.isNotEmpty()) {
      val block = queue.poll()
      block.run()
    }

    while (true) {
//            println("delayedContinuation while begin count=" + delayQueue.size)

      val delayedContinuation = delayQueue.poll() ?: break
      println("delayedContinuation returned")
      with(delayedContinuation.continuation) { resumeUndispatched(Unit) }
    }

    return true
  }

  fun isDone() = queue.isEmpty() && callbackQueue.isEmpty() && delayQueue.isEmpty()

  override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
    println("scheduleResumeAfterDelay delay=$timeMillis")
    delayQueue.add(DelayedContinuation(timeMillis, continuation))
  }

  private class DelayedContinuation(
    private val delayTime: Long,
    val continuation: CancellableContinuation<Unit>
  ) : Delayed {
    private val startTime = System.currentTimeMillis() + delayTime

    override fun compareTo(other: Delayed): Int {
      return (getDelay(TimeUnit.MILLISECONDS) - other.getDelay(TimeUnit.MILLISECONDS)).toInt()
    }

    override fun getDelay(unit: TimeUnit): Long {
      val diff = startTime - System.currentTimeMillis()
      return unit.convert(diff, TimeUnit.MILLISECONDS)
    }
  }
}

/**
 * Dispatcher used to schedule callback coroutines which should run before any other coroutines.
 * This is to avoid signal loss due to UnhandledCommand.
 */
@OptIn(InternalCoroutinesApi::class)
class TemporalCallbackCoroutineDispatcher(val dispatcher: TemporalCoroutineDispatcher) : CoroutineDispatcher(), Delay {

  override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
    dispatcher.scheduleResumeAfterDelay(timeMillis, continuation)
  }

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    dispatcher.dispatchCallback(context, block)
  }
}

internal class TemporalScope(private val workflowContext: KotlinWorkflowContext) : CoroutineScope {
  // TODO: Add argument to the Temporal context.
  override val coroutineContext: CoroutineContext = TemporalCoroutineContext(workflowContext)

  // CoroutineScope is used intentionally for user-friendly representation
  override fun toString(): String = "CoroutineScope(coroutineContext=$coroutineContext)"
}

class TemporalCoroutineContext(val workflowContext: KotlinWorkflowContext) :
  AbstractCoroutineContextElement(TemporalCoroutineContext) {
  override val key = Key

  companion object Key : CoroutineContext.Key<TemporalCoroutineContext>
}
