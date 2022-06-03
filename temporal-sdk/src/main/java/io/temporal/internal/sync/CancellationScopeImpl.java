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

package io.temporal.internal.sync;

import io.temporal.workflow.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

class CancellationScopeImpl implements CancellationScope {

  private static final ThreadLocal<Deque<CancellationScopeImpl>> scopeStack =
      ThreadLocal.withInitial(ArrayDeque::new);
  private boolean detached;
  private CompletablePromise<String> cancellationPromise;

  static CancellationScopeImpl current() {
    if (scopeStack.get().isEmpty()) {
      throw new IllegalStateException("Cannot be called by non workflow thread");
    }
    return scopeStack.get().peekFirst();
  }

  private static void pushCurrent(CancellationScopeImpl scope) {
    scopeStack.get().addFirst(scope);
  }

  private static void popCurrent(CancellationScopeImpl expected) {
    CancellationScopeImpl current = scopeStack.get().pollFirst();
    if (current != expected) {
      throw new Error("Unexpected scope");
    }
    if (!current.detached && current.cancellationPromise == null && current.children.isEmpty()) {
      current.parent.removeChild(current);
    }
  }

  private final Runnable runnable;
  private CancellationScopeImpl parent;
  private final Set<CancellationScopeImpl> children = new HashSet<>();
  /**
   * When disconnected scope has no parent and thus doesn't receive cancellation requests from it.
   */
  private boolean cancelRequested;

  private String reason;

  CancellationScopeImpl(boolean ignoreParentCancellation, Runnable runnable) {
    this(ignoreParentCancellation, runnable, current());
  }

  CancellationScopeImpl(boolean detached, Runnable runnable, CancellationScopeImpl parent) {
    this.detached = detached;
    this.runnable = runnable;
    setParent(parent);
  }

  public CancellationScopeImpl(
      boolean ignoreParentCancellation, Functions.Proc1<CancellationScope> proc) {
    this.detached = ignoreParentCancellation;
    this.runnable = () -> proc.apply(this);
    setParent(current());
  }

  private void setParent(CancellationScopeImpl parent) {
    if (parent == null) {
      detached = true;
      return;
    }
    if (!detached) {
      this.parent = parent;
      parent.addChild(this);
      if (parent.isCancelRequested()) {
        cancel(parent.getCancellationReason());
      }
    }
  }

  @Override
  public void run() {
    try {
      pushCurrent(this);
      runnable.run();
    } finally {
      popCurrent(this);
    }
  }

  @Override
  public boolean isDetached() {
    return detached;
  }

  @Override
  public void cancel() {
    cancelRequested = true;
    reason = null;
    for (CancellationScopeImpl child : children) {
      child.cancel();
    }
    if (cancellationPromise != null) {
      cancellationPromise.complete(null);
      cancellationPromise = null;
    }
  }

  @Override
  public void cancel(String reason) {
    cancelRequested = true;
    this.reason = reason;
    for (CancellationScopeImpl child : children) {
      child.cancel(reason);
    }
    if (cancellationPromise != null) {
      cancellationPromise.complete(reason);
      cancellationPromise = null;
    }
  }

  @Override
  public String getCancellationReason() {
    return reason;
  }

  @Override
  public boolean isCancelRequested() {
    return cancelRequested;
  }

  @Override
  public CompletablePromise<String> getCancellationRequest() {
    if (cancellationPromise == null) {
      cancellationPromise = Workflow.newPromise();
      if (isCancelRequested()) {
        cancellationPromise.complete(getCancellationReason());
      }
    }
    return cancellationPromise;
  }

  private void addChild(CancellationScopeImpl scope) {
    children.add(scope);
  }

  private void removeChild(CancellationScopeImpl scope) {
    if (!children.remove(scope)) {
      throw new Error("Not a child");
    }
  }
}
