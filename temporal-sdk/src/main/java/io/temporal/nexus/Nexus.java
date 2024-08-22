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

package io.temporal.nexus;

import io.temporal.common.Experimental;
import io.temporal.internal.nexus.NexusInternal;
import io.temporal.internal.sync.WorkflowInternal;

/** This class contains methods exposing Temporal APIs for Nexus Operations */
@Experimental
public final class Nexus {
  /**
   * Can be used to get information about a Nexus Operation. This static method relies on a
   * thread-local variable and works only in the original Nexus thread.
   */
  public static NexusOperationContext getExecutionContext() {
    return NexusInternal.getExecutionContext();
  }

  /**
   * Use this to rethrow a checked exception from a Nexus Operation instead of adding the exception
   * to a method signature.
   *
   * @return Never returns; always throws. Throws original exception if e is {@link
   *     RuntimeException} or {@link Error}.
   */
  public static RuntimeException wrap(Throwable e) {
    return WorkflowInternal.wrap(e);
  }

  /** Prohibits instantiation. */
  private Nexus() {}
}
