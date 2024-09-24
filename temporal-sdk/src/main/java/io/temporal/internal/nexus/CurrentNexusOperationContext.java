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

package io.temporal.internal.nexus;

/**
 * Thread local store of the context object passed to a nexus operation implementation. Not to be
 * used directly.
 */
public final class CurrentNexusOperationContext {
  private static final ThreadLocal<NexusOperationContextImpl> CURRENT = new ThreadLocal<>();

  public static NexusOperationContextImpl get() {
    NexusOperationContextImpl result = CURRENT.get();
    if (result == null) {
      throw new IllegalStateException(
          "NexusOperationContext can be used only inside of nexus operation handler "
              + "implementation methods and in the same thread that invoked the operation.");
    }
    return CURRENT.get();
  }

  public static void set(NexusOperationContextImpl context) {
    if (context == null) {
      throw new IllegalArgumentException("null context");
    }
    if (CURRENT.get() != null) {
      throw new IllegalStateException("current already set");
    }
    CURRENT.set(context);
  }

  public static void unset() {
    CURRENT.set(null);
  }

  private CurrentNexusOperationContext() {}
}
