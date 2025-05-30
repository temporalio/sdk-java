package io.temporal.internal.sync;

import io.temporal.workflow.*;
import java.util.*;

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
  // We use a LinkedHashSet because we will iterate through the children, so we need to keep a
  // deterministic order.
  private final Set<CancellationScopeImpl> children;

  /**
   * When disconnected scope has no parent and thus doesn't receive cancellation requests from it.
   */
  private boolean cancelRequested;

  private String reason;

  CancellationScopeImpl(
      boolean ignoreParentCancellation, boolean deterministicOrder, Runnable runnable) {
    this(ignoreParentCancellation, deterministicOrder, runnable, current());
  }

  CancellationScopeImpl(
      boolean detached,
      boolean deterministicOrder,
      Runnable runnable,
      CancellationScopeImpl parent) {
    this.detached = detached;
    this.runnable = runnable;
    if (deterministicOrder) {
      this.children = new LinkedHashSet<>();
    } else {
      this.children = new HashSet<>();
    }
    setParent(parent);
  }

  public CancellationScopeImpl(
      boolean ignoreParentCancellation,
      boolean deterministicOrder,
      Functions.Proc1<CancellationScope> proc) {
    this.detached = ignoreParentCancellation;
    this.runnable = () -> proc.apply(this);
    if (deterministicOrder) {
      this.children = new LinkedHashSet<>();
    } else {
      this.children = new HashSet<>();
    }
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
