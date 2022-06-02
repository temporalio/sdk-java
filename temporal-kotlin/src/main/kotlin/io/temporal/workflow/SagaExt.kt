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

import io.temporal.kotlin.TemporalDsl

/**
 * Builds an instance of [Saga].
 *
 * ```kotlin
 * val saga = Saga {
 *     // saga options
 * }
 * try {
 *   val r = activity.foo()
 *   saga.addCompensation { activity.cleanupFoo(arg2, r) }
 *   val r2 = Async.function { activity.bar() }
 *   r2.thenApply { r2result ->
 *       saga.addCompensation { activity.cleanupBar(r2result) }
 *   }
 *   // ...
 *   useR2(r2.get())
 * } catch (Exception e) {
 *    saga.compensate()
 *    // Other error handling if needed.
 * }
 * ```
 *
 * @see Saga
 * @see Saga.Options
 */
inline fun Saga(options: @TemporalDsl Saga.Options.Builder.() -> Unit = {}): Saga {
  return Saga(Saga.Options.Builder().apply(options).build())
}
