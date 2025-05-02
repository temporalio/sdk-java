package io.temporal.workflow;

public interface WorkflowQueue<E> extends QueueConsumer<E>, QueueProducer<E> {}
