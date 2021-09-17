/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
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

import io.temporal.kotlin.TemporalDsl

/**
 * Builds an instance of [Saga] that implements the logic to execute
 * [compensation operations](https://en.wikipedia.org/wiki/Compensating_transaction) that is often
 * required in Saga applications. The following is a skeleton to show of how it is supposed to be
 * used in workflow code:
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
 */
inline fun Saga(options: @TemporalDsl Saga.Options.Builder.() -> Unit = {}): Saga {
  return Saga(Saga.Options.Builder().apply(options).build())
}
