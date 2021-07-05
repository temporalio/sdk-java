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

package io.temporal.workflow;

import com.uber.m3.tally.Scope;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.internal.sync.WorkflowInternal;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Functions.Func;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import org.slf4j.Logger;

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
 *   <li>{@literal @}{@link WorkflowMethod} indicates an entry point to a workflow. It contains
 *       parameters such as timeouts and a task queue. Required parameters (like {@code
 *       workflowRunTimeoutSeconds}) that are not specified through the annotation must be provided
 *       at runtime.
 *   <li>{@literal @}{@link SignalMethod} indicates a method that reacts to external signals. It
 *       must have a {@code void} return type.
 *   <li>{@literal @}{@link QueryMethod} indicates a method that reacts to synchronous query
 *       requests. You can have more than one method with the same annotation.
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
 * WorkflowMethod} is invoked. As soon as this method returns the workflow, execution is closed.
 * While workflow execution is open, it can receive calls to signal and query methods. No additional
 * calls to workflow methods are allowed. The workflow object is stateful, so query and signal
 * methods can communicate with the other parts of the workflow through workflow object fields.
 *
 * <h3>Calling Activities</h3>
 *
 * {@link #newActivityStub(Class)} returns a client-side stub that implements an activity interface.
 * It takes activity type and activity options as arguments. Activity options are needed only if
 * some of the required timeouts are not specified through the {@literal @}{@link
 * io.temporal.activity.ActivityMethod} annotation.
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
 * Sometimes workflows need to perform certain operations in parallel. The {@link Async} static
 * methods allow you to invoke any activity asynchronously. The call returns a {@link Promise}
 * result immediately. {@link Promise} is similar to both {@link java.util.concurrent.Future} and
 * {@link java.util.concurrent.CompletionStage}. The {@link Promise#get()} blocks until a result is
 * available. It also exposes the {@link Promise#thenApply(Functions.Func1)} and {@link
 * Promise#handle(Functions.Func2)} methods. See the {@link Promise} documentation for technical
 * details about differences with {@link java.util.concurrent.Future}.
 *
 * <p>To convert a synchronous call
 *
 * <pre><code>
 * String localName = activities.download(sourceBucket, sourceFile);
 * </code></pre>
 *
 * to asynchronous style, the method reference is passed to {@link Async#function(Functions.Func)}
 * or {@link Async#procedure(Functions.Proc)} followed by activity arguments:
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
 * <p>{@link #newChildWorkflowStub(Class)} returns a client-side stub that implements a child
 * workflow interface. It takes a child workflow type and optional child workflow options as
 * arguments. Workflow options may be needed to override the timeouts and task queue if they differ
 * from the ones defined in the {@literal @}{@link WorkflowMethod} annotation or parent workflow.
 *
 * <p>The first call to the child workflow stub must always be to a method annotated with
 * {@literal @}{@link WorkflowMethod}. Similarly to activities, a call can be synchronous or
 * asynchronous using {@link Async#function(Functions.Func)} or {@link
 * Async#procedure(Functions.Proc)}. The synchronous call blocks until a child workflow completes.
 * The asynchronous call returns a {@link Promise} that can be used to wait for the completion.
 * After an async call returns the stub, it can be used to send signals to the child by calling
 * methods annotated with {@literal @}{@link SignalMethod}. Querying a child workflow by calling
 * methods annotated with {@literal @}{@link QueryMethod} from within workflow code is not
 * supported. However, queries can be done from activities using the {@link
 * io.temporal.client.WorkflowClient} provided stub.
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
 * To send signal to a child, call a method annotated with {@literal @}{@link SignalMethod}:
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
 * Calling methods annotated with {@literal @}{@link QueryMethod} is not allowed from within a
 * workflow code.
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
 *   <li>Do not call any non deterministic functions like non seeded random or {@link
 *       UUID#randomUUID()} directly form the workflow code. Always do this in activities.
 *   <li>Don’t perform any IO or service calls as they are not usually deterministic. Use activities
 *       for this.
 *   <li>Only use {@link #currentTimeMillis()} to get the current time inside a workflow.
 *   <li>Do not use native Java {@link Thread} or any other multi-threaded classes like {@link
 *       java.util.concurrent.ThreadPoolExecutor}. Use {@link Async#function(Functions.Func)} or
 *       {@link Async#procedure(Functions.Proc)} to execute code asynchronously.
 *   <li>Don't use any synchronization, locks, and other standard Java blocking concurrency-related
 *       classes besides those provided by the Workflow class. There is no need in explicit
 *       synchronization because multi-threaded code inside a workflow is executed one thread at a
 *       time and under a global lock.
 *   <li>Call {@link #sleep(Duration)} instead of {@link Thread#sleep(long)}.
 *   <li>Use {@link Promise} and {@link CompletablePromise} instead of {@link
 *       java.util.concurrent.Future} and {@link java.util.concurrent.CompletableFuture}.
 *   <li>Use {@link WorkflowQueue} instead of {@link java.util.concurrent.BlockingQueue}.
 *   <li>Don't change workflow code when there are open workflows. The ability to do updates through
 *       visioning is TBD.
 *   <li>Don’t access configuration APIs directly from a workflow because changes in the
 *       configuration might affect a workflow execution path. Pass it as an argument to a workflow
 *       function or use an activity to load it.
 * </ul>
 *
 * <p>Workflow method arguments and return values are serializable to a byte array using the
 * provided {@link io.temporal.common.converter.DataConverter}. The default implementation uses the
 * JSON serializer, but any alternative serialization mechanism is pluggable.
 *
 * <p>The values passed to workflows through invocation parameters or returned through a result
 * value are recorded in the execution history. The entire execution history is transferred from the
 * Temporal service to workflow workers with every event that the workflow logic needs to process. A
 * large execution history can thus adversely impact the performance of your workflow. Therefore, be
 * mindful of the amount of data that you transfer via activity invocation parameters or return
 * values. Other than that, no additional limitations exist on activity implementations.
 */
public final class Workflow {
  public static final int DEFAULT_VERSION = WorkflowInternal.DEFAULT_VERSION;

  public static void setDefaultActivityOptions(ActivityOptions defaultActivityOptions) {
    WorkflowInternal.setDefaultActivityOptions(defaultActivityOptions);
  }

  public static void setActivityOptions(Map<String, ActivityOptions> activityMethodOptions) {
    WorkflowInternal.setActivityOptions(activityMethodOptions);
  }

  /**
   * Creates client stub to activities that implement given interface. `
   *
   * @param activityInterface interface type implemented by activities
   */
  public static <T> T newActivityStub(Class<T> activityInterface) {
    return WorkflowInternal.newActivityStub(activityInterface, null, null);
  }

  /**
   * Creates client stub to activities that implement given interface
   *
   * @param activityInterface interface type implemented by activities.
   * @param options options that together with the properties of {@link
   *     io.temporal.activity.ActivityMethod} specify the activity invocation parameters
   */
  public static <T> T newActivityStub(Class<T> activityInterface, ActivityOptions options) {
    return WorkflowInternal.newActivityStub(activityInterface, options, null);
  }

  /**
   * Creates client stub to activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   * @param options options that together with the properties of {@link
   *     io.temporal.activity.ActivityMethod} specify the activity invocation parameters
   * @param activityMethodOptions activity method-specific invocation parameters
   */
  public static <T> T newActivityStub(
      Class<T> activityInterface,
      ActivityOptions options,
      Map<String, ActivityOptions> activityMethodOptions) {
    return WorkflowInternal.newActivityStub(activityInterface, options, activityMethodOptions);
  }

  /**
   * Creates non typed client stub to activities. Allows executing activities by their string name.
   *
   * @param options specify the activity invocation parameters.
   */
  public static ActivityStub newUntypedActivityStub(ActivityOptions options) {
    return WorkflowInternal.newUntypedActivityStub(options);
  }

  /**
   * Creates client stub to local activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   */
  public static <T> T newLocalActivityStub(Class<T> activityInterface) {
    return WorkflowInternal.newLocalActivityStub(activityInterface, null, null);
  }

  /**
   * Creates client stub to local activities that implement given interface. A local activity is
   * similar to a regular activity, but with some key differences: 1. Local activity is scheduled
   * and run by the workflow worker locally. 2. Local activity does not need Temporal server to
   * schedule activity task and does not rely on activity worker. 3. Local activity is for short
   * living activities (usually finishes within seconds). 4. Local activity cannot heartbeat.
   *
   * @param activityInterface interface type implemented by activities
   * @param options options that together with the properties of {@link
   *     io.temporal.activity.ActivityMethod} specify the activity invocation parameters.
   */
  public static <T> T newLocalActivityStub(
      Class<T> activityInterface, LocalActivityOptions options) {
    return WorkflowInternal.newLocalActivityStub(activityInterface, options, null);
  }

  /**
   * Creates client stub to local activities that implement given interface.
   *
   * @param activityInterface interface type implemented by activities
   * @param options options that together with the properties of {@link
   *     io.temporal.activity.ActivityMethod} specify the activity invocation parameters
   * @param activityMethodOptions activity method-specific invocation parameters
   */
  public static <T> T newLocalActivityStub(
      Class<T> activityInterface,
      LocalActivityOptions options,
      Map<String, LocalActivityOptions> activityMethodOptions) {
    return WorkflowInternal.newLocalActivityStub(activityInterface, options, activityMethodOptions);
  }

  /**
   * Creates non typed client stub to local activities. Allows executing activities by their string
   * name.
   *
   * @param options specify the local activity invocation parameters.
   */
  public static ActivityStub newUntypedLocalActivityStub(LocalActivityOptions options) {
    return WorkflowInternal.newUntypedLocalActivityStub(options);
  }

  /**
   * Creates client stub that can be used to start a child workflow that implements the given
   * interface using parent options. Use {@link #newExternalWorkflowStub(Class, String)} to get a
   * stub to signal a workflow without starting it.
   *
   * @param workflowInterface interface type implemented by activities
   */
  public static <T> T newChildWorkflowStub(Class<T> workflowInterface) {
    return WorkflowInternal.newChildWorkflowStub(workflowInterface, null);
  }

  /**
   * Creates client stub that can be used to start a child workflow that implements given interface.
   * Use {@link #newExternalWorkflowStub(Class, String)} to get a stub to signal a workflow without
   * starting it.
   *
   * @param workflowInterface interface type implemented by activities
   * @param options options passed to the child workflow.
   */
  public static <T> T newChildWorkflowStub(
      Class<T> workflowInterface, ChildWorkflowOptions options) {
    return WorkflowInternal.newChildWorkflowStub(workflowInterface, options);
  }

  /**
   * Creates client stub that can be used to communicate to an existing workflow execution.
   *
   * @param workflowInterface interface type implemented by activities
   * @param workflowId id of the workflow to communicate with.
   */
  public static <R> R newExternalWorkflowStub(
      Class<? extends R> workflowInterface, String workflowId) {
    WorkflowExecution execution = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
    return WorkflowInternal.newExternalWorkflowStub(workflowInterface, execution);
  }

  /**
   * Creates client stub that can be used to communicate to an existing workflow execution.
   *
   * @param workflowInterface interface type implemented by activities
   * @param execution execution of the workflow to communicate with.
   */
  public static <R> R newExternalWorkflowStub(
      Class<? extends R> workflowInterface, WorkflowExecution execution) {
    return WorkflowInternal.newExternalWorkflowStub(workflowInterface, execution);
  }

  /**
   * Extracts workflow execution from a stub created through {@link #newChildWorkflowStub(Class,
   * ChildWorkflowOptions)} or {@link #newExternalWorkflowStub(Class, String)}. Wrapped in a Promise
   * as child workflow start is asynchronous.
   */
  public static Promise<WorkflowExecution> getWorkflowExecution(Object childWorkflowStub) {
    return WorkflowInternal.getWorkflowExecution(childWorkflowStub);
  }

  /**
   * Creates untyped client stub that can be used to start and signal a child workflow.
   *
   * @param workflowType name of the workflow type to start.
   * @param options options passed to the child workflow.
   */
  public static ChildWorkflowStub newUntypedChildWorkflowStub(
      String workflowType, ChildWorkflowOptions options) {
    return WorkflowInternal.newUntypedChildWorkflowStub(workflowType, options);
  }

  /**
   * Creates untyped client stub that can be used to start and signal a child workflow. All options
   * are inherited from the parent.
   *
   * @param workflowType name of the workflow type to start.
   */
  public static ChildWorkflowStub newUntypedChildWorkflowStub(String workflowType) {
    return WorkflowInternal.newUntypedChildWorkflowStub(workflowType, null);
  }

  /**
   * Creates untyped client stub that can be used to signal or cancel a child workflow.
   *
   * @param execution execution of the workflow to communicate with.
   */
  public static ExternalWorkflowStub newUntypedExternalWorkflowStub(WorkflowExecution execution) {
    return WorkflowInternal.newUntypedExternalWorkflowStub(execution);
  }

  /**
   * Creates untyped client stub that can be used to signal or cancel a child workflow.
   *
   * @param workflowId id of the workflow to communicate with.
   */
  public static ExternalWorkflowStub newUntypedExternalWorkflowStub(String workflowId) {
    WorkflowExecution execution = WorkflowExecution.newBuilder().setWorkflowId(workflowId).build();
    return Workflow.newUntypedExternalWorkflowStub(execution);
  }

  /**
   * Creates a client stub that can be used to continue this workflow as a new run.
   *
   * @param workflowInterface an interface type implemented by the next run of the workflow
   */
  public static <T> T newContinueAsNewStub(
      Class<T> workflowInterface, ContinueAsNewOptions options) {
    return WorkflowInternal.newContinueAsNewStub(workflowInterface, options);
  }

  /**
   * Creates a client stub that can be used to continue this workflow as a new run.
   *
   * @param workflowInterface an interface type implemented by the next run of the workflow
   */
  public static <T> T newContinueAsNewStub(Class<T> workflowInterface) {
    return WorkflowInternal.newContinueAsNewStub(workflowInterface, null);
  }

  /**
   * Continues the current workflow execution as a new run with the same options.
   *
   * @param args arguments of the next run.
   * @see #newContinueAsNewStub(Class)
   */
  public static void continueAsNew(Object... args) {
    Workflow.continueAsNew(Optional.empty(), Optional.empty(), args);
  }

  /**
   * Continues the current workflow execution as a new run possibly overriding the workflow type and
   * options.
   *
   * @param options option overrides for the next run.
   * @param args arguments of the next run.
   * @see #newContinueAsNewStub(Class)
   */
  public static void continueAsNew(
      Optional<String> workflowType, Optional<ContinueAsNewOptions> options, Object... args) {
    WorkflowInternal.continueAsNew(workflowType, options, args);
  }

  public static WorkflowInfo getInfo() {
    return WorkflowInternal.getWorkflowInfo();
  }

  /**
   * Wraps the Runnable method argument in a {@link CancellationScope}. The {@link
   * CancellationScope#run()} calls {@link Runnable#run()} on the wrapped Runnable. The returned
   * CancellationScope can be used to cancel the wrapped code. The cancellation semantic depends on
   * the operation the code is blocked on. For example activity or child workflow is first canceled
   * then throws a {@link CanceledFailure}. The same applies for {@link Workflow#sleep(long)}
   * operation. When an activity or a child workflow is invoked asynchronously then they get
   * canceled and a {@link Promise} that contains their result will throw {@link CanceledFailure}
   * when {@link Promise#get()} is called.
   *
   * <p>The new cancellation scope is linked to the parent one (available as {@link
   * CancellationScope#current()}. If the parent one is canceled then all the children scopes are
   * canceled automatically. The main workflow function (annotated with @{@link WorkflowMethod} is
   * wrapped within a root cancellation scope which gets canceled when a workflow is canceled
   * through the Temporal CancelWorkflowExecution API. To perform cleanup operations that require
   * blocking after the current scope is canceled use a scope created through {@link
   * #newDetachedCancellationScope(Runnable)}.
   *
   * <p>Example of running activities in parallel and cancelling them after a specified timeout.
   *
   * <pre><code>
   *     List&lt;Promise&lt;String&gt;&gt; results = new ArrayList&lt;&gt;();
   *     CancellationScope scope = Workflow.newDetachedCancellationScope(() -&gt; {
   *        Async.function(activities::a1);
   *        Async.function(activities::a2);
   *     });
   *     scope.run(); // returns immediately as the activities are invoked asynchronously
   *     Workflow.sleep(Duration.ofHours(1));
   *     // Cancels any activity in the scope that is still running
   *     scope.cancel("one hour passed");
   *
   * </code></pre>
   *
   * @param runnable parameter to wrap in a cancellation scope.
   * @return wrapped parameter.
   */
  public static CancellationScope newCancellationScope(Runnable runnable) {
    return WorkflowInternal.newCancellationScope(false, runnable);
  }

  /**
   * Wraps a procedure in a CancellationScope. The procedure receives the wrapping CancellationScope
   * as a parameter. Useful when cancellation is requested from within the wrapped code. The
   * following example cancels the sibling activity on any failure.
   *
   * <pre><code>
   *               Workflow.newCancellationScope(
   *                   (scope) -&gt; {
   *                     Promise<Void> p1 = Async.proc(activities::a1).exceptionally(ex-&gt;
   *                        {
   *                           scope.cancel("a1 failed");
   *                           return null;
   *                        });
   *
   *                     Promise<Void> p2 = Async.proc(activities::a2).exceptionally(ex-&gt;
   *                        {
   *                           scope.cancel("a2 failed");
   *                           return null;
   *                        });
   *                     Promise.allOf(p1, p2).get();
   *                   })
   *               .run();
   * </code></pre>
   *
   * @param proc code to wrap in the cancellation scope
   * @return wrapped proc
   */
  public static CancellationScope newCancellationScope(Functions.Proc1<CancellationScope> proc) {
    return WorkflowInternal.newCancellationScope(false, proc);
  }

  /**
   * Creates a CancellationScope that is not linked to a parent scope. {@link
   * CancellationScope#run()} must be called to execute the code the scope wraps. The detached scope
   * is needed to execute cleanup code after a workflow is canceled which cancels the root scope
   * that wraps the @WorkflowMethod invocation. Here is an example usage:
   *
   * <pre><code>
   *  try {
   *     // workflow logic
   *  } catch (CanceledFailure e) {
   *     CancellationScope detached = Workflow.newDetachedCancellationScope(() -&gt; {
   *         // cleanup logic
   *     });
   *     detached.run();
   *  }
   * </code></pre>
   *
   * @param runnable parameter to wrap in a cancellation scope.
   * @return wrapped parameter.
   * @see #newCancellationScope(Runnable)
   */
  public static CancellationScope newDetachedCancellationScope(Runnable runnable) {
    return WorkflowInternal.newCancellationScope(true, runnable);
  }

  /**
   * Create new timer. Note that Temporal service time resolution is in seconds. So all durations
   * are rounded <b>up</b> to the nearest second.
   *
   * @return feature that becomes ready when at least specified number of seconds passes. promise is
   *     failed with {@link CanceledFailure} if enclosing scope is canceled.
   */
  public static Promise<Void> newTimer(Duration delay) {
    return WorkflowInternal.newTimer(delay);
  }

  @Deprecated
  public static <E> WorkflowQueue<E> newQueue(int capacity) {
    return WorkflowInternal.newQueue(capacity);
  }

  /**
   * Create a new instance of a {@link WorkflowQueue} implementation that is adapted to be used from
   * a workflow code.
   *
   * @param capacity the maximum size of the queue
   * @return new instance of {@link WorkflowQueue}
   */
  public static <E> WorkflowQueue<E> newWorkflowQueue(int capacity) {
    return WorkflowInternal.newWorkflowQueue(capacity);
  }

  public static <E> CompletablePromise<E> newPromise() {
    return WorkflowInternal.newCompletablePromise();
  }

  public static <E> Promise<E> newPromise(E value) {
    return WorkflowInternal.newPromise(value);
  }

  public static <E> Promise<E> newFailedPromise(Exception failure) {
    return WorkflowInternal.newFailedPromise(failure);
  }

  /**
   * Registers an implementation object. The object must implement at least one interface annotated
   * with {@link WorkflowInterface}. All its methods annotated with @{@link SignalMethod}
   * and @{@link QueryMethod} are registered.
   *
   * <p>There is no need to register the top level workflow implementation object as it is done
   * implicitly by the framework on object startup.
   *
   * <p>An attempt to register a duplicated query is going to fail with {@link
   * IllegalArgumentException}
   */
  public static void registerListener(Object listener) {
    WorkflowInternal.registerListener(listener);
  }

  /**
   * Must be used to get current time instead of {@link System#currentTimeMillis()} to guarantee
   * determinism.
   */
  public static long currentTimeMillis() {
    return WorkflowInternal.currentTimeMillis();
  }

  /** Must be called instead of {@link Thread#sleep(long)} to guarantee determinism. */
  public static void sleep(Duration duration) {
    WorkflowInternal.sleep(duration);
  }

  /** Must be called instead of {@link Thread#sleep(long)} to guarantee determinism. */
  public static void sleep(long millis) {
    WorkflowInternal.sleep(Duration.ofMillis(millis));
  }

  /**
   * Block current thread until unblockCondition is evaluated to true.
   *
   * @param unblockCondition condition that should return true to indicate that thread should
   *     unblock. The condition is called on every state transition, so it should never call any
   *     blocking operations or contain code that mutates any workflow state. It should also not
   *     contain any time based conditions. Use {@link #await(Duration, Supplier)} for those
   *     instead.
   * @throws CanceledFailure if thread (or current {@link CancellationScope} was canceled).
   */
  public static void await(Supplier<Boolean> unblockCondition) {
    WorkflowInternal.await(
        "await",
        () -> {
          CancellationScope.throwCanceled();
          return unblockCondition.get();
        });
  }

  /**
   * Block current workflow thread until unblockCondition is evaluated to true or timeoutMillis
   * passes.
   *
   * @param timeout time to unblock even if unblockCondition is not satisfied.
   * @param unblockCondition condition that should return true to indicate that thread should
   *     unblock. The condition is called on every state transition, so it should not contain any
   *     code that mutates any workflow state. It should also not contain any time based conditions.
   *     Use timeout parameter for those.
   * @return false if timed out.
   * @throws CanceledFailure if thread (or current {@link CancellationScope} was canceled).
   */
  public static boolean await(Duration timeout, Supplier<Boolean> unblockCondition) {
    return WorkflowInternal.await(
        timeout,
        "await",
        () -> {
          CancellationScope.throwCanceled();
          return unblockCondition.get();
        });
  }

  /**
   * Invokes function retrying in case of failures according to retry options. Synchronous variant.
   * Use {@link Async#retry(RetryOptions, Optional, Functions.Func)} for asynchronous functions.
   *
   * @param options retry options that specify retry policy
   * @param expiration stop retrying after this interval if provided
   * @param fn function to invoke and retry
   * @return result of the function or the last failure.
   */
  public static <R> R retry(
      RetryOptions options, Optional<Duration> expiration, Functions.Func<R> fn) {
    return WorkflowInternal.retry(options, expiration, fn);
  }

  /**
   * Invokes function retrying in case of failures according to retry options. Synchronous variant.
   * Use {@link Async#retry(RetryOptions, Optional, Functions.Func)} (RetryOptions, Optional, Func)}
   * for asynchronous functions.
   *
   * @param options retry options that specify retry policy
   * @param expiration if specified stop retrying after this interval
   * @param proc procedure to invoke and retry
   */
  public static void retry(
      RetryOptions options, Optional<Duration> expiration, Functions.Proc proc) {
    WorkflowInternal.retry(
        options,
        expiration,
        () -> {
          proc.apply();
          return null;
        });
  }

  /**
   * If there is a need to return a checked exception from a workflow implementation do not add the
   * exception to a method signature but wrap it using this method before rethrowing. The library
   * code will unwrap it automatically using when propagating exception to a remote caller. {@link
   * RuntimeException} are just returned from this method without modification.
   *
   * <p>The reason for such design is that returning originally thrown exception from a remote call
   * (which child workflow and activity invocations are ) would not allow adding context information
   * about a failure, like activity and child workflow id. So stubs always throw a subclass of
   * {@link ActivityFailure} from calls to an activity and subclass of {@link ChildWorkflowFailure}
   * from calls to a child workflow. The original exception is attached as a cause to these wrapper
   * exceptions. So as exceptions are always wrapped adding checked ones to method signature causes
   * more pain than benefit.
   *
   * <p>
   *
   * <pre>
   * try {
   *     return someCall();
   * } catch (Exception e) {
   *     throw Workflow.wrap(e);
   * }
   * </pre>
   *
   * *
   *
   * @return CheckedExceptionWrapper if e is checked or original exception if e extends
   *     RuntimeException.
   */
  public static RuntimeException wrap(Exception e) {
    return WorkflowInternal.wrap(e);
  }

  /**
   * Replay safe way to generate UUID.
   *
   * <p>Must be used instead of {@link UUID#randomUUID()} which relies on a random generator, thus
   * leads to non deterministic code which is prohibited inside a workflow.
   */
  public static UUID randomUUID() {
    return WorkflowInternal.randomUUID();
  }

  /** Replay safe random numbers generator. Seeded differently for each workflow instance. */
  public static Random newRandom() {
    return WorkflowInternal.newRandom();
  }

  /**
   * True if workflow code is being replayed. <b>Warning!</b> Never make workflow logic depend on
   * this flag as it is going to break determinism. The only reasonable uses for this flag are
   * deduping external never failing side effects like logging or metric reporting.
   *
   * <p>This method always returns false if called from a non workflow thread.
   */
  public static boolean isReplaying() {
    return WorkflowInternal.isReplaying();
  }

  /**
   * Executes the provided function once, records its result into the workflow history. The recorded
   * result on history will be returned without executing the provided function during replay. This
   * guarantees the deterministic requirement for workflow as the exact same result will be returned
   * in replay. Common use case is to run some short non-deterministic code in workflow, like
   * getting random number. The only way to fail SideEffect is to panic which causes workflow task
   * failure. The workflow task after timeout is rescheduled and re-executed giving SideEffect
   * another chance to succeed.
   *
   * <p>Caution: do not use sideEffect function to modify any workflow state. Only use the
   * SideEffect's return value. For example this code is BROKEN:
   *
   * <pre><code>
   *  // Bad example:
   *  AtomicInteger random = new AtomicInteger();
   *  Workflow.sideEffect(() -&gt; {
   *         random.set(random.nextInt(100));
   *         return null;
   *  });
   *  // random will always be 0 in replay, thus this code is non-deterministic
   *  if random.get() &lt; 50 {
   *         ....
   *  } else {
   *         ....
   *  }
   * </code></pre>
   *
   * On replay the provided function is not executed, the random will always be 0, and the workflow
   * could takes a different path breaking the determinism.
   *
   * <p>Here is the correct way to use sideEffect:
   *
   * <pre><code>
   *  // Good example:
   *  int random = Workflow.sideEffect(Integer.class, () -&gt; random.nextInt(100));
   *  if random &lt; 50 {
   *         ....
   *  } else {
   *         ....
   *  }
   * </code></pre>
   *
   * If function throws any exception it is not delivered to the workflow code. It is wrapped in
   * {@link Error} causing failure of the current workflow task.
   *
   * @param resultClass type of the side effect
   * @param func function that returns side effect value
   * @return value of the side effect
   * @see #mutableSideEffect(String, Class, BiPredicate, Functions.Func)
   */
  public static <R> R sideEffect(Class<R> resultClass, Func<R> func) {
    return WorkflowInternal.sideEffect(resultClass, resultClass, func);
  }

  /**
   * Executes the provided function once, records its result into the workflow history. The recorded
   * result on history will be returned without executing the provided function during replay. This
   * guarantees the deterministic requirement for workflow as the exact same result will be returned
   * in replay. Common use case is to run some short non-deterministic code in workflow, like
   * getting random number. The only way to fail SideEffect is to panic which causes workflow task
   * failure. The workflow task after timeout is rescheduled and re-executed giving SideEffect
   * another chance to succeed.
   *
   * <p>Caution: do not use sideEffect function to modify any workflow state. Only use the
   * SideEffect's return value. For example this code is BROKEN:
   *
   * <pre><code>
   *  // Bad example:
   *  AtomicInteger random = new AtomicInteger();
   *  Workflow.sideEffect(() -&gt; {
   *         random.set(random.nextInt(100));
   *         return null;
   *  });
   *  // random will always be 0 in replay, thus this code is non-deterministic
   *  if random.get() &lt; 50 {
   *         ....
   *  } else {
   *         ....
   *  }
   * </code></pre>
   *
   * On replay the provided function is not executed, the random will always be 0, and the workflow
   * could takes a different path breaking the determinism.
   *
   * <p>Here is the correct way to use sideEffect:
   *
   * <pre><code>
   *  // Good example:
   *  int random = Workflow.sideEffect(Integer.class, () -&gt; random.nextInt(100));
   *  if random &lt; 50 {
   *         ....
   *  } else {
   *         ....
   *  }
   * </code></pre>
   *
   * If function throws any exception it is not delivered to the workflow code. It is wrapped in
   * {@link Error} causing failure of the current workflow task.
   *
   * @param resultClass class of the side effect
   * @param resultType type of the side effect. Differs from resultClass for generic types.
   * @param func function that returns side effect value
   * @return value of the side effect
   * @see #mutableSideEffect(String, Class, BiPredicate, Functions.Func)
   */
  public static <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
    return WorkflowInternal.sideEffect(resultClass, resultType, func);
  }

  /**
   * {@code mutableSideEffect} is similar to {@link #sideEffect(Class, Functions.Func)} in allowing
   * calls of non-deterministic functions from workflow code.
   *
   * <p>The difference between {@code mutableSideEffect} and {@link #sideEffect(Class,
   * Functions.Func)} is that every new {@code sideEffect} call in non-replay mode results in a new
   * marker event recorded into the history. However, {@code mutableSideEffect} only records a new
   * marker if a value has changed. During the replay, {@code mutableSideEffect} will not execute
   * the function again, but it will return the exact same value as it was returning during the
   * non-replay run.
   *
   * <p>One good use case of {@code mutableSideEffect} is to access a dynamically changing config
   * without breaking determinism. Even if called very frequently the config value is recorded only
   * when it changes not causing any performance degradation due to a large history size.
   *
   * <p>Caution: do not use {@code mutableSideEffect} function to modify any workflow state. Only
   * use the mutableSideEffect's return value.
   *
   * <p>If function throws any exception it is not delivered to the workflow code. It is wrapped in
   * {@link Error} causing failure of the current workflow task.
   *
   * @param id unique identifier of this side effect
   * @param updated used to decide if a new value should be recorded. A func result is recorded only
   *     if call to updated with stored and a new value as arguments returns true. It is not called
   *     for the first value.
   * @param resultClass class of the side effect
   * @param func function that produces a value. This function can contain non deterministic code.
   * @see #sideEffect(Class, Functions.Func)
   */
  public static <R> R mutableSideEffect(
      String id, Class<R> resultClass, BiPredicate<R, R> updated, Func<R> func) {
    return WorkflowInternal.mutableSideEffect(id, resultClass, resultClass, updated, func);
  }

  /**
   * {@code mutableSideEffect} is similar to {@link #sideEffect(Class, Functions.Func)} in allowing
   * calls of non-deterministic functions from workflow code.
   *
   * <p>The difference between {@code mutableSideEffect} and {@link #sideEffect(Class,
   * Functions.Func)} is that every new {@code sideEffect} call in non-replay mode results in a new
   * marker event recorded into the history. However, {@code mutableSideEffect} only records a new
   * marker if a value has changed. During the replay, {@code mutableSideEffect} will not execute
   * the function again, but it will return the exact same value as it was returning during the
   * non-replay run.
   *
   * <p>One good use case of {@code mutableSideEffect} is to access a dynamically changing config
   * without breaking determinism. Even if called very frequently the config value is recorded only
   * when it changes not causing any performance degradation due to a large history size.
   *
   * <p>Caution: do not use {@code mutableSideEffect} function to modify any workflow state. Only
   * use the mutableSideEffect's return value.
   *
   * <p>If function throws any exception it is not delivered to the workflow code. It is wrapped in
   * {@link Error} causing failure of the current workflow task.
   *
   * @param id unique identifier of this side effect
   * @param updated used to decide if a new value should be recorded. A func result is recorded only
   *     if call to updated with stored and a new value as arguments returns true. It is not called
   *     for the first value.
   * @param resultClass class of the side effect
   * @param resultType type of the side effect. Differs from resultClass for generic types.
   * @param func function that produces a value. This function can contain non deterministic code.
   * @see #sideEffect(Class, Functions.Func)
   */
  public static <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
    return WorkflowInternal.mutableSideEffect(id, resultClass, resultType, updated, func);
  }

  /**
   * {@code getVersion} is used to safely perform backwards incompatible changes to workflow
   * definitions. It is not allowed to update workflow code while there are workflows running as it
   * is going to break determinism. The solution is to have both old code that is used to replay
   * existing workflows as well as the new one that is used when it is executed for the first time.\
   *
   * <p>{@code getVersion} returns maxSupported version when is executed for the first time. This
   * version is recorded into the workflow history as a marker event. Even if maxSupported version
   * is changed the version that was recorded is returned on replay. DefaultVersion constant
   * contains version of code that wasn't versioned before.
   *
   * <p>For example initially workflow has the following code:
   *
   * <pre><code>
   * result = testActivities.activity1();
   * </code></pre>
   *
   * it should be updated to
   *
   * <pre><code>
   * result = testActivities.activity2();
   * </code></pre>
   *
   * The backwards compatible way to execute the update is
   *
   * <pre><code>
   * int version = Workflow.getVersion("fooChange", Workflow.DEFAULT_VERSION, 1);
   * String result;
   * if (version == Workflow.DEFAULT_VERSION) {
   *   result = testActivities.activity1();
   * } else {
   *   result = testActivities.activity2();
   * }
   * </code></pre>
   *
   * Then later if we want to have another change:
   *
   * <pre><code>
   * int version = Workflow.getVersion("fooChange", Workflow.DEFAULT_VERSION, 2);
   * String result;
   * if (version == Workflow.DEFAULT_VERSION) {
   *   result = testActivities.activity1();
   * } else if (version == 1) {
   *   result = testActivities.activity2();
   * } else {
   *   result = testActivities.activity3();
   * }
   * </code></pre>
   *
   * Later when there are no workflow executions running DefaultVersion the correspondent branch can
   * be removed:
   *
   * <pre><code>
   * int version = Workflow.getVersion("fooChange", 1, 2);
   * String result;
   * if (version == 1) {
   *   result = testActivities.activity2();
   * } else {
   *   result = testActivities.activity3();
   * }
   * </code></pre>
   *
   * It is recommended to keep the GetVersion() call even if single branch is left:
   *
   * <pre><code>
   * Workflow.getVersion("fooChange", 2, 2);
   * result = testActivities.activity3();
   * </code></pre>
   *
   * The reason to keep it is: 1) it ensures that if there is older version execution still running,
   * it will fail here and not proceed; 2) if you ever need to make more changes for “fooChange”,
   * for example change activity3 to activity4, you just need to update the maxVersion from 2 to 3.
   *
   * <p>Note that, you only need to preserve the first call to GetVersion() for each changeId. All
   * subsequent call to GetVersion() with same changeId are safe to remove. However, if you really
   * want to get rid of the first GetVersion() call as well, you can do so, but you need to make
   * sure: 1) all older version executions are completed; 2) you can no longer use “fooChange” as
   * changeId. If you ever need to make changes to that same part, you would need to use a different
   * changeId like “fooChange-fix2”, and start minVersion from DefaultVersion again.
   *
   * @param changeId identifier of a particular change. All calls to getVersion that share a
   *     changeId are guaranteed to return the same version number. Use this to perform multiple
   *     coordinated changes that should be enabled together.
   * @param minSupported min version supported for the change
   * @param maxSupported max version supported for the change
   * @return version
   */
  public static int getVersion(String changeId, int minSupported, int maxSupported) {
    return WorkflowInternal.getVersion(changeId, minSupported, maxSupported);
  }

  /**
   * Get scope for reporting business metrics in workflow logic. This should be used instead of
   * creating new metrics scopes as it is able to dedup metrics during replay.
   *
   * <p>The original metrics scope is set through {@link WorkerOptions} when a worker starts up.
   */
  public static Scope getMetricsScope() {
    return WorkflowInternal.getMetricsScope();
  }

  /**
   * Get logger to use inside workflow. Logs in replay mode are omitted unless enableLoggingInReplay
   * is set to true in {@link WorkerOptions} when a worker starts up.
   *
   * @param clazz class name to appear in logging.
   * @return logger to use in workflow logic.
   */
  public static Logger getLogger(Class<?> clazz) {
    return WorkflowInternal.getLogger(clazz);
  }

  /**
   * Get logger to use inside workflow. Logs in replay mode are omitted unless enableLoggingInReplay
   * is set to true in {@link WorkerOptions} when a worker starts up.
   *
   * @param name name to appear in logging.
   * @return logger to use in workflow logic.
   */
  public static Logger getLogger(String name) {
    return WorkflowInternal.getLogger(name);
  }

  /**
   * GetLastCompletionResult extract last completion result from previous run for this cron
   * workflow. This is used in combination with cron schedule. A workflow can be started with an
   * optional cron schedule. If a cron workflow wants to pass some data to next schedule, it can
   * return any data and that data will become available when next run starts.
   *
   * @param resultClass class of the return data from last run
   * @return result of last run
   */
  public static <R> R getLastCompletionResult(Class<R> resultClass) {
    return WorkflowInternal.getLastCompletionResult(resultClass, resultClass);
  }

  /**
   * Extract the latest failure from some previous of this workflow. If any previous run of this
   * workflow has failed, this function returns that failure. If no previous runs have failed, an
   * empty optional is returned. The run you are calling this from may have been created as a retry
   * of the previous failed run or as a next cron invocation for cron workflows.
   *
   * @return The last {@link Exception} that occurred in this workflow, if there has been one.
   */
  public static Optional<Exception> getPreviousRunFailure() {
    return WorkflowInternal.getPreviousRunFailure();
  }

  /**
   * GetLastCompletionResult extract last completion result from previous run for this cron
   * workflow. This is used in combination with cron schedule. A workflow can be started with an
   * optional cron schedule. If a cron workflow wants to pass some data to next schedule, it can
   * return any data and that data will become available when next run starts.
   *
   * @param resultClass class of the return data from last run
   * @param resultType type of the return data from last run. Differs from resultClass for generic
   *     types.
   * @return result of last run
   */
  public static <R> R getLastCompletionResult(Class<R> resultClass, Type resultType) {
    return WorkflowInternal.getLastCompletionResult(resultClass, resultType);
  }

  /**
   * {@code upsertSearchAttributes} is used to add or update workflow search attributes. The search
   * attributes can be used in query of List/Scan/Count workflow APIs. The key and value type must
   * be registered on Temporal server side; The value has to be Json serializable.
   * UpsertSearchAttributes will merge attributes to existing map in workflow, for example workflow
   * code:
   *
   * <pre><code>
   *     Map&lt;String, Object&gt; attr1 = new HashMap&lt;&gt;();
   *     attr1.put("CustomIntField", 1);
   *     attr1.put("CustomBoolField", true);
   *     Workflow.upsertSearchAttributes(attr1);
   *
   *     Map&lt;String, Object&gt; attr2 = new HashMap&lt;&gt;();
   *     attr2.put("CustomIntField", 2);
   *     attr2.put("CustomKeywordField", "Seattle");
   *     Workflow.upsertSearchAttributes(attr2);
   * </pre></code> will eventually have search attributes as:
   *
   * <pre><code>
   *     {
   *       "CustomIntField": 2,
   *       "CustomBoolField": true,
   *       "CustomKeywordField": "Seattle",
   *     }
   * </pre></code>
   *
   * @param searchAttributes map of String to Object value that can be used to search in list APIs
   */
  public static void upsertSearchAttributes(Map<String, Object> searchAttributes) {
    WorkflowInternal.upsertSearchAttributes(searchAttributes);
  }

  /** Prohibit instantiation. */
  private Workflow() {}
}
