package io.temporal.internal.sync;

import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Functions;
import io.temporal.workflow.QueueConsumer;
import io.temporal.workflow.WorkflowQueue;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * @deprecated it's an old implementation of {@link WorkflowQueue} with incorrectly implemented
 *     {@link #take} and {@link #cancellableTake} that is left for backwards-compatibility with
 *     workflows that already use old implementation. {@link WorkflowQueueImpl} should be used
 *     instead.
 *     <p>This class is to be deleted in the next major release that doesn't have to maintain
 *     backwards compatibility.
 */
@Deprecated
final class WorkflowQueueDeprecatedImpl<E> implements WorkflowQueue<E> {

  private final Deque<E> queue = new ArrayDeque<>();
  private final int capacity;

  public WorkflowQueueDeprecatedImpl(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Capacity less than 1: " + capacity);
    }
    this.capacity = capacity;
  }

  @Override
  public E take() {
    WorkflowThread.await("WorkflowQueue.take", () -> !queue.isEmpty());
    // this implementation is incorrect and has been fixed in WorkflowQueueImpl
    return queue.pollLast();
  }

  @Override
  public E cancellableTake() {
    WorkflowThread.await(
        "WorkflowQueue.cancellableTake",
        () -> {
          CancellationScope.throwCanceled();
          return !queue.isEmpty();
        });
    // this implementation is incorrect and has been fixed in WorkflowQueueImpl
    return queue.pollLast();
  }

  @Override
  public E poll() {
    if (queue.isEmpty()) {
      return null;
    }
    return queue.remove();
  }

  @Override
  public E peek() {
    if (queue.isEmpty()) {
      return null;
    }
    return queue.peek();
  }

  @Override
  public E poll(Duration timeout) {
    WorkflowInternal.await(timeout, "WorkflowQueue.poll", () -> !queue.isEmpty());

    if (queue.isEmpty()) {
      return null;
    }
    return queue.remove();
  }

  @Override
  public E cancellablePoll(Duration timeout) {
    WorkflowInternal.await(
        timeout,
        "WorkflowQueue.cancellablePoll",
        () -> {
          CancellationScope.throwCanceled();
          return !queue.isEmpty();
        });

    if (queue.isEmpty()) {
      return null;
    }
    return queue.remove();
  }

  @Override
  public boolean offer(E e) {
    if (queue.size() == capacity) {
      return false;
    }
    queue.addLast(e);
    return true;
  }

  @Override
  public void put(E e) {
    WorkflowThread.await("WorkflowQueue.put", () -> queue.size() < capacity);
    queue.addLast(e);
  }

  @Override
  public void cancellablePut(E e) {
    WorkflowThread.await(
        "WorkflowQueue.cancellablePut",
        () -> {
          CancellationScope.throwCanceled();
          return queue.size() < capacity;
        });
    queue.addLast(e);
  }

  @Override
  public boolean offer(E e, Duration timeout) {
    WorkflowInternal.await(timeout, "WorkflowQueue.offer", () -> queue.size() < capacity);
    if (queue.size() >= capacity) {
      return false;
    }
    queue.addLast(e);
    return true;
  }

  @Override
  public boolean cancellableOffer(E e, Duration timeout) {
    WorkflowInternal.await(
        timeout, "WorkflowQueue.cancellableOffer", () -> queue.size() < capacity);
    if (queue.size() >= capacity) {
      return false;
    }
    queue.addLast(e);
    return true;
  }

  @Override
  public <R> QueueConsumer<R> map(Functions.Func1<? super E, ? extends R> mapper) {
    return new MappedQueueConsumer<R, E>(this, mapper);
  }

  private static class MappedQueueConsumer<R, E> implements QueueConsumer<R> {

    private final QueueConsumer<E> source;
    private final Functions.Func1<? super E, ? extends R> mapper;

    public MappedQueueConsumer(
        QueueConsumer<E> source, Functions.Func1<? super E, ? extends R> mapper) {
      this.source = source;
      this.mapper = mapper;
    }

    @Override
    public R take() {
      E element = source.take();
      return mapper.apply(element);
    }

    @Override
    public R cancellableTake() {
      E element = source.cancellableTake();
      return mapper.apply(element);
    }

    @Override
    public R poll() {
      E element = source.poll();
      if (element == null) {
        return null;
      }
      return mapper.apply(element);
    }

    @Override
    public R peek() {
      E element = source.peek();
      if (element == null) {
        return null;
      }
      return mapper.apply(element);
    }

    @Override
    public R poll(Duration timeout) {
      E element = source.poll(timeout);
      if (element == null) {
        return null;
      }
      return mapper.apply(element);
    }

    @Override
    public R cancellablePoll(Duration timeout) {
      E element = source.cancellablePoll(timeout);
      if (element == null) {
        return null;
      }
      return mapper.apply(element);
    }

    @Override
    public <R1> QueueConsumer<R1> map(Functions.Func1<? super R, ? extends R1> mapper) {
      return new MappedQueueConsumer<>(this, mapper);
    }
  }
}
