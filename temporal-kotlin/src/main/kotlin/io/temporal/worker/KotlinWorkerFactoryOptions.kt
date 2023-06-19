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
package io.temporal.worker

import com.google.common.base.Preconditions
import io.temporal.kotlin.interceptors.KotlinWorkerInterceptor
import java.time.Duration

class KotlinWorkerFactoryOptions private constructor(
  workflowCacheSize: Int,
  workflowHostLocalTaskQueueScheduleToStartTimeout: Duration?,
  workerInterceptors: Array<out KotlinWorkerInterceptor>,
  enableLoggingInReplay: Boolean,
  validate: Boolean
) {
  class Builder {
    private var workflowHostLocalTaskQueueScheduleToStartTimeout: Duration? = null
    private var workflowCacheSize = 0
    private var workerInterceptors: Array<out KotlinWorkerInterceptor> = emptyArray()
    private var enableLoggingInReplay = false

    internal constructor() {}
    internal constructor(options: KotlinWorkerFactoryOptions?) {
      if (options == null) {
        return
      }
      workflowHostLocalTaskQueueScheduleToStartTimeout = options.workflowHostLocalTaskQueueScheduleToStartTimeout
      workflowCacheSize = options.workflowCacheSize
      workerInterceptors = options.workerInterceptors
      enableLoggingInReplay = options.isEnableLoggingInReplay
    }

    /**
     * To avoid constant replay of code the workflow objects are cached on a worker. This cache is
     * shared by all workers created by the Factory. Note that in the majority of situations the
     * number of cached workflows is limited not by this value, but by the number of the threads
     * defined through [.setMaxWorkflowThreadCount].
     *
     *
     * Default value is 600
     */
    fun setWorkflowCacheSize(workflowCacheSize: Int): Builder {
      this.workflowCacheSize = workflowCacheSize
      return this
    }

    /**
     * Timeout for a workflow task routed to the "sticky worker" - host that has the workflow
     * instance cached in memory. Once it times out, then it can be picked up by any worker.
     *
     *
     * Default value is 5 seconds.
     *
     */
    @Deprecated(
      """use {@link WorkerOptions.Builder#setStickyQueueScheduleToStartTimeout(Duration)}
          to specify this value per-worker instead"""
    )
    fun setWorkflowHostLocalTaskQueueScheduleToStartTimeout(timeout: Duration?): Builder {
      workflowHostLocalTaskQueueScheduleToStartTimeout = timeout
      return this
    }

    fun setWorkerInterceptors(vararg workerInterceptors: KotlinWorkerInterceptor): Builder {
      this.workerInterceptors = workerInterceptors
      return this
    }

    fun setEnableLoggingInReplay(enableLoggingInReplay: Boolean): Builder {
      this.enableLoggingInReplay = enableLoggingInReplay
      return this
    }

    @Deprecated("not used anymore by JavaSDK, this value doesn't have any effect")
    fun setWorkflowHostLocalPollThreadCount(workflowHostLocalPollThreadCount: Int): Builder {
      return this
    }

    fun build(): KotlinWorkerFactoryOptions {
      return KotlinWorkerFactoryOptions(
        workflowCacheSize,
        workflowHostLocalTaskQueueScheduleToStartTimeout,
        workerInterceptors,
        enableLoggingInReplay,
        false
      )
    }

    fun validateAndBuildWithDefaults(): KotlinWorkerFactoryOptions {
      return KotlinWorkerFactoryOptions(
        workflowCacheSize,
        workflowHostLocalTaskQueueScheduleToStartTimeout,
        workerInterceptors,
        enableLoggingInReplay,
        true
      )
    }
  }

  val workflowCacheSize: Int
  val workflowHostLocalTaskQueueScheduleToStartTimeout: Duration?
  val workerInterceptors: Array<out KotlinWorkerInterceptor>
  val isEnableLoggingInReplay: Boolean

  init {
    var workflowCacheSize = workflowCacheSize
    var workerInterceptors = workerInterceptors
    if (validate) {
      Preconditions.checkState(workflowCacheSize >= 0, "negative workflowCacheSize")
      if (workflowCacheSize <= 0) {
        workflowCacheSize = DEFAULT_WORKFLOW_CACHE_SIZE
      }
      if (workflowHostLocalTaskQueueScheduleToStartTimeout != null) {
        Preconditions.checkState(
          !workflowHostLocalTaskQueueScheduleToStartTimeout.isNegative,
          "negative workflowHostLocalTaskQueueScheduleToStartTimeoutSeconds"
        )
      }
    }
    this.workflowCacheSize = workflowCacheSize
    this.workflowHostLocalTaskQueueScheduleToStartTimeout = workflowHostLocalTaskQueueScheduleToStartTimeout
    this.workerInterceptors = workerInterceptors
    isEnableLoggingInReplay = enableLoggingInReplay
  }

  fun toBuilder(): Builder {
    return Builder(this)
  }

  companion object {
    fun newBuilder(): Builder {
      return Builder()
    }

    fun newBuilder(options: KotlinWorkerFactoryOptions?): Builder {
      return Builder(options)
    }

    private const val DEFAULT_WORKFLOW_CACHE_SIZE = 600
    private const val DEFAULT_MAX_WORKFLOW_THREAD_COUNT = 600
    val defaultInstance: KotlinWorkerFactoryOptions = newBuilder().build()
  }
}
