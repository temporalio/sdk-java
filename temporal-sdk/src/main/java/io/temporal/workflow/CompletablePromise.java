package io.temporal.workflow;

/** {@link Promise} that exposes completion methods. */
public interface CompletablePromise<V> extends Promise<V> {

  /**
   * Completes this Promise with a value if not yet done.
   *
   * @return true if wasn't already completed.
   */
  boolean complete(V value);

  /**
   * Completes this Promise with a an exception if not yet done.
   *
   * @return true if wasn't already completed.
   */
  boolean completeExceptionally(RuntimeException value);

  /**
   * Completes or completes exceptionally this promise from the source promise when it becomes
   * completed.
   *
   * <pre><code>
   * destination.completeFrom(source);
   * </code></pre>
   *
   * Is shortcut to:
   *
   * <pre><code>
   * source.handle((value, failure) -&gt; {
   *    if (failure != null) {
   *       destination.completeExceptionally(failure);
   *    } else {
   *       destination.complete(value);
   *    }
   *    return null;
   * }
   * </code></pre>
   *
   * @param source promise that is being watched.
   * @return false if source already completed, otherwise return true or null
   */
  boolean completeFrom(Promise<V> source);
}
