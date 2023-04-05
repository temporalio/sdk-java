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

package io.temporal.internal.worker;

public enum WorkerLifecycleState {
  /** The worker was created but never started */
  NOT_STARTED,
  /** Ready to accept and process tasks */
  ACTIVE,
  /** May be absent from a worker state machine is the worker is not {@link Suspendable} */
  SUSPENDED,
  /**
   * Shutdown is requested on the worker, and it is completing with the outstanding tasks before
   * being {@link #TERMINATED}. The worker MAY reject new tasks to be processed if any internals are
   * already being released.
   */
  SHUTDOWN,
  /**
   * The final state of the worker, all internal resources are released, the worker SHOULD reject
   * any new tasks to be processed
   */
  TERMINATED
}
