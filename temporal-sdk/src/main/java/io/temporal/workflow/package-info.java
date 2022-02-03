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

/**
 * Workflow encapsulates the orchestration of activities and child workflows. It can also answer to
 * synchronous queries and receive external events (also known as signals).
 *
 * <h2>Workflow Interface</h2>
 *
 * A workflow must define an interface class. All of its methods must have one of the following
 * annotations:
 *
 * <ul>
 *   <li>{@literal @}{@link io.temporal.workflow.WorkflowMethod} indicates an entry point to a
 *       workflow. It contains parameters such as timeouts and a task queue. Required parameters
 *       (like {@code workflowRunTimeoutSeconds}) that are not specified through the annotation must
 *       be provided at runtime.
 *   <li>{@literal @}{@link io.temporal.workflow.SignalMethod} indicates a method that reacts to
 *       external signals. It must have a {@code void} return type.
 *   <li>{@literal @}{@link io.temporal.workflow.QueryMethod} indicates a method that reacts to
 *       synchronous query requests. You can have more than one method with the same annotation.
 * </ul>
 *
 * <pre><code>
 * public interface FileProcessingWorkflow {
 *
 *    {@literal @}WorkflowMethod(workflowRunTimeoutSeconds = 10, taskQueue = "file-processing")
 *     String processFile(Arguments args);
 *
 *    {@literal @}QueryMethod(name="history")
 *     List<String> getHistory();
 *
 *    {@literal @}QueryMethod(name="status")
 *     String getStatus();
 *
 *    {@literal @}SignalMethod
 *     void retryNow();
 * }
 * </code></pre>
 *
 * <h2>Starting workflow executions</h2>
 *
 * See {@link io.temporal.client.WorkflowClient}
 *
 * <h2>Implementing Workflows</h2>
 *
 * A workflow implementation implements a workflow interface. Each time a new workflow execution is
 * started, a new instance of the workflow implementation object is created. Then, one of the
 * methods (depending on which workflow type has been started) annotated with {@literal @}{@link
 * io.temporal.workflow.WorkflowMethod} is invoked. As soon as this method returns the workflow,
 * execution is closed. While workflow execution is open, it can receive calls to signal and query
 * methods. No additional calls to workflow methods are allowed. The workflow object is stateful, so
 * query and signal methods can communicate with the other parts of the workflow through workflow
 * object fields.
 *
 * <h3>Calling Activities</h3>
 *
 * {@link io.temporal.workflow.Workflow#newActivityStub(Class)} returns a client-side stub that
 * implements an activity interface. It takes activity type and activity options as arguments.
 * Activity options are needed only if some of the required timeouts are not specified through the
 * {@literal @}{@link io.temporal.activity.ActivityMethod} annotation.
 *
 * <p>Calling a method on this interface invokes an activity that implements this method. An
 * activity invocation synchronously blocks until the activity completes, fails, or times out. Even
 * if activity execution takes a few months, the workflow code still sees it as a single synchronous
 * invocation. Isn't it great? It doesn't matter what happens to the processes that host the
 * workflow. The business logic code just sees a single method call.
 *
 * <pre><code>
 * public class FileProcessingWorkflowImpl implements FileProcessingWorkflow {
 *
 *     private final FileProcessingActivities activities;
 *
 *     public FileProcessingWorkflowImpl() {
 *         this.store = Workflow.newActivityStub(FileProcessingActivities.class);
 *     }
 *
 *    {@literal @}Override
 *     public void processFile(Arguments args) {
 *         String localName = null;
 *         String processedName = null;
 *         try {
 *             localName = activities.download(args.getSourceBucketName(), args.getSourceFilename());
 *             processedName = activities.processFile(localName);
 *             activities.upload(args.getTargetBucketName(), args.getTargetFilename(), processedName);
 *         } finally {
 *             if (localName != null) { // File was downloaded.
 *                 activities.deleteLocalFile(localName);
 *             }
 *             if (processedName != null) { // File was processed.
 *                 activities.deleteLocalFile(processedName);
 *             }
 *         }
 *     }
 *     ...
 * }
 * </code></pre>
 *
 * If different activities need different options, like timeouts or a task queue, multiple
 * client-side stubs can be created with different options.
 *
 * <pre><code>
 * public FileProcessingWorkflowImpl() {
 *     ActivityOptions options1 = ActivityOptions.newBuilder()
 *         .setTaskQueue("taskQueue1")
 *         .build();
 *     this.store1 = Workflow.newActivityStub(FileProcessingActivities.class, options1);
 *
 *     ActivityOptions options2 = ActivityOptions.newBuilder()
 *         .setTaskQueue("taskQueue2")
 *         .build();
 *     this.store2 = Workflow.newActivityStub(FileProcessingActivities.class, options2);
 * }
 * </code></pre>
 *
 * <h3>Calling Activities Asynchronously</h3>
 *
 * Sometimes workflows need to perform certain operations in parallel. The {@link
 * io.temporal.workflow.Async} static methods allow you to invoke any activity asynchronously. The
 * call returns a {@link io.temporal.workflow.Promise} result immediately. {@link
 * io.temporal.workflow.Promise} is similar to both {@link java.util.concurrent.Future} and {@link
 * java.util.concurrent.CompletionStage}. The {@link io.temporal.workflow.Promise#get()} blocks
 * until a result is available. It also exposes the {@link
 * io.temporal.workflow.Promise#thenApply(Functions.Func1)} and {@link
 * io.temporal.workflow.Promise#handle(Functions.Func2)} methods. See the {@link
 * io.temporal.workflow.Promise} documentation for technical details about differences with {@link
 * java.util.concurrent.Future}.
 *
 * <p>To convert a synchronous call
 *
 * <pre><code>
 * String localName = activities.download(sourceBucket, sourceFile);
 * </code></pre>
 *
 * to asynchronous style, the method reference is passed to {@link
 * io.temporal.workflow.Async#function(Functions.Func)} or {@link
 * io.temporal.workflow.Async#procedure(Functions.Proc)} followed by activity arguments:
 *
 * <pre><code>
 * Promise<String> localNamePromise = Async.function(activities::download, sourceBucket, sourceFile);
 * </code></pre>
 *
 * Then to wait synchronously for the result:
 *
 * <pre><code>
 * String localName = localNamePromise.get();
 * </code></pre>
 *
 * Here is the above example rewritten to call download and upload in parallel on multiple files:
 *
 * <pre>{@code
 * public void processFile(Arguments args) {
 *     List<Promise<String>> localNamePromises = new ArrayList<>();
 *     List<String> processedNames = null;
 *     try {
 *         // Download all files in parallel.
 *         for (String sourceFilename : args.getSourceFilenames()) {
 *             Promise<String> localName = Async.function(activities::download, args.getSourceBucketName(), sourceFilename);
 *             localNamePromises.add(localName);
 *         }
 *         // allOf converts a list of promises to a single promise that contains a list of each promise value.
 *         Promise<List<String>> localNamesPromise = Promise.allOf(localNamePromises);
 *
 *         // All code until the next line wasn't blocking.
 *         // The promise get is a blocking call.
 *         List<String> localNames = localNamesPromise.get();
 *         processedNames = activities.processFiles(localNames);
 *
 *         // Upload all results in parallel.
 *         List<Promise<Void>> uploadedList = new ArrayList<>();
 *         for (String processedName : processedNames) {
 *             Promise<Void> uploaded = Async.procedure(activities::upload,
 *                 args.getTargetBucketName(),
 *                 args.getTargetFilename(),
 *                 processedName);
 *             uploadedList.add(uploaded);
 *         }
 *         // Wait for all uploads to complete.
 *         Promise<?> allUploaded = Promise.allOf(uploadedList);
 *         allUploaded.get(); // blocks until all promises are ready.
 *     } finally {
 *         // Execute deletes even if workflow is canceled.
 *         Workflow.newDetachedCancellationScope(
 *             () -> {
 *                 for (Promise<Sting> localNamePromise : localNamePromises) {
 *                     // Skip files that haven't completed downloading.
 *                     if (localNamePromise.isCompleted()) {
 *                         activities.deleteLocalFile(localNamePromise.get());
 *                     }
 *                 }
 *                 if (processedNames != null) {
 *                     for (String processedName : processedNames) {
 *                         activities.deleteLocalFile(processedName);
 *                     }
 *                 }
 *             }
 *          ).run();
 *     }
 * }
 * }</pre>
 *
 * <h3>Child Workflows</h3>
 *
 * Besides activities, a workflow can also orchestrate other workflows.
 *
 * <p>{@link io.temporal.workflow.Workflow#newChildWorkflowStub(Class)} returns a client-side stub
 * that implements a child workflow interface. It takes a child workflow type and optional child
 * workflow options as arguments. Workflow options may be needed to override the timeouts and task
 * queue if they differ from the ones defined in the {@literal @}{@link
 * io.temporal.workflow.WorkflowMethod} annotation or parent workflow.
 *
 * <p>The first call to the child workflow stub must always be to a method annotated with
 * {@literal @}{@link io.temporal.workflow.WorkflowMethod}. Similarly to activities, a call can be
 * synchronous or asynchronous using {@link io.temporal.workflow.Async#function(Functions.Func)} or
 * {@link io.temporal.workflow.Async#procedure(Functions.Proc)}. The synchronous call blocks until a
 * child workflow completes. The asynchronous call returns a {@link io.temporal.workflow.Promise}
 * that can be used to wait for the completion. After an async call returns the stub, it can be used
 * to send signals to the child by calling methods annotated with {@literal @}{@link
 * io.temporal.workflow.SignalMethod}. Querying a child workflow by calling methods annotated with
 * {@literal @}{@link io.temporal.workflow.QueryMethod} from within workflow code is not supported.
 * However, queries can be done from activities using the {@link io.temporal.client.WorkflowClient}
 * provided stub.
 *
 * <pre><code>
 * public interface GreetingChild {
 *    {@literal @}WorkflowMethod
 *     String composeGreeting(String greeting, String name);
 * }
 *
 * public static class GreetingWorkflowImpl implements GreetingWorkflow {
 *
 *    {@literal @}Override
 *     public String getGreeting(String name) {
 *         GreetingChild child = Workflow.newChildWorkflowStub(GreetingChild.class);
 *
 *         // This is a blocking call that returns only after child has completed.
 *         return child.composeGreeting("Hello", name );
 *     }
 * }
 * </code></pre>
 *
 * Running two children in parallel:
 *
 * <pre><code>
 * public static class GreetingWorkflowImpl implements GreetingWorkflow {
 *
 *    {@literal @}Override
 *     public String getGreeting(String name) {
 *
 *         // Workflows are stateful, so a new stub must be created for each new child.
 *         GreetingChild child1 = Workflow.newChildWorkflowStub(GreetingChild.class);
 *         Promise<String> greeting1 = Async.function(child1::composeGreeting, "Hello", name);
 *
 *         // Both children will run concurrently.
 *         GreetingChild child2 = Workflow.newChildWorkflowStub(GreetingChild.class);
 *         Promise<String> greeting2 = Async.function(child2::composeGreeting, "Bye", name);
 *
 *         // Do something else here.
 *         ...
 *         return "First: " + greeting1.get() + ", second=" + greeting2.get();
 *     }
 * }
 * </code></pre>
 *
 * To send signal to a child, call a method annotated with {@literal @}{@link
 * io.temporal.workflow.SignalMethod}:
 *
 * <pre><code>
 * public interface GreetingChild {
 *    {@literal @}WorkflowMethod
 *     String composeGreeting(String greeting, String name);
 *
 *    {@literal @}SignalMethod
 *     void updateName(String name);
 * }
 *
 * public static class GreetingWorkflowImpl implements GreetingWorkflow {
 *
 *    {@literal @}Override
 *     public String getGreeting(String name) {
 *         GreetingChild child = Workflow.newChildWorkflowStub(GreetingChild.class);
 *         Promise<String> greeting = Async.function(child::composeGreeting, "Hello", name);
 *         child.updateName("Temporal");
 *         return greeting.get();
 *     }
 * }
 * </code></pre>
 *
 * Calling methods annotated with {@literal @}{@link io.temporal.workflow.QueryMethod} is not
 * allowed from within a workflow code.
 *
 * <h3>Workflow Implementation Constraints</h3>
 *
 * Temporal uses <a
 * href="https://docs.microsoft.com/en-us/azure/architecture/patterns/event-sourcing">event
 * sourcing</a> to recover the state of a workflow object including its threads and local variable
 * values. In essence, every time a workflow state has to be restored, its code is re-executed from
 * the beginning. When replaying, side effects (such as activity invocations) are ignored because
 * they are already recorded in the workflow event history. When writing workflow logic, the replay
 * is not visible, so the code should be written as it executes only once. This design puts the
 * following constraints on the workflow implementation:
 *
 * <ul>
 *   <li>Do not use any mutable global variables because multiple instances of workflows are
 *       executed in parallel.
 *   <li>Do not call any non-deterministic functions, like non-seeded random, directly form the
 *       workflow code. Always use safe deterministic alternatives provided by Temporal SDK on
 *       {@link io.temporal.workflow.Workflow} or perform such calls in activities if have to. For
 *       example:
 *       <ul>
 *         <li>Use {@link io.temporal.workflow.Workflow#currentTimeMillis()} instead of {@link
 *             java.lang.System#currentTimeMillis()} to get the current time inside a workflow
 *         <li>Use {@link io.temporal.workflow.Workflow#randomUUID()} instead of {@link
 *             java.util.UUID#randomUUID()}
 *       </ul>
 *   <li>Don't perform long blocking operations other than calls that block inside Temporal SDK
 *       (like Activity invocations or {@link io.temporal.workflow.Workflow} APIs). Use activities
 *       for this. For example:
 *       <ul>
 *         <li>Call {@link io.temporal.workflow.Workflow#sleep(Duration)} instead of {@link
 *             java.lang.Thread#sleep(long)}.
 *         <li>Use {@link io.temporal.workflow.Promise} and {@link
 *             io.temporal.workflow.CompletablePromise} instead of {@link
 *             java.util.concurrent.Future} and {@link java.util.concurrent.CompletableFuture}.
 *       </ul>
 *   <li>Don’t perform any IO or service calls as they are blocking and usually not deterministic.
 *       Use activities for long running or non-deterministic code.
 *   <li>Do not use native Java {@link java.lang.Thread} or any other multi-threaded classes like
 *       {@link java.util.concurrent.ThreadPoolExecutor}. Use {@link
 *       io.temporal.workflow.Async#function(Functions.Func)} or {@link
 *       io.temporal.workflow.Async#procedure(Functions.Proc)} to execute code asynchronously.
 *   <li>Don't use any synchronization, locks, and other standard Java blocking concurrency-related
 *       classes besides those provided by the Workflow class. There is no need in explicit
 *       synchronization because multi-threaded code inside a single workflow execution is executed
 *       one thread at a time and under a global lock. For example:
 *       <ul>
 *         <li>Use {@link io.temporal.workflow.WorkflowQueue} instead of {@link
 *             java.util.concurrent.BlockingQueue}.
 *       </ul>
 *   <li>Use Workflow.getVersion when making any changes to the Workflow code. Without this, any
 *       deployment of updated Workflow code might break already running Workflows.
 *   <li>Don’t access configuration APIs directly from a workflow because changes in the
 *       configuration might affect a workflow execution path. Pass it as an argument to a workflow
 *       function or use an activity to load it.
 * </ul>
 *
 * <h3>Parameters and return values serialization</h3>
 *
 * Workflow method arguments and return values are serializable to a byte array using the provided
 * {@link io.temporal.common.converter.DataConverter}. The default implementation uses the JSON
 * serializer, but any alternative serialization mechanism is pluggable.
 *
 * <p>The values passed to workflows through invocation parameters or returned through a result
 * value are recorded in the execution history. The entire execution history is transferred from the
 * Temporal service to workflow workers with every event that the workflow logic needs to process. A
 * large execution history can thus adversely impact the performance of your workflow. Therefore, be
 * mindful of the amount of data that you transfer via activity invocation parameters or return
 * values. Other than that, no additional limitations exist on activity implementations.
 */
package io.temporal.workflow;

import java.time.Duration;
