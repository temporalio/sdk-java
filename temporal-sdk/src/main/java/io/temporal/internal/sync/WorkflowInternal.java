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

import static io.temporal.internal.sync.AsyncInternal.AsyncMarker;
import static io.temporal.internal.sync.DeterministicRunnerImpl.currentThreadInternal;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.uber.m3.tally.Scope;
import io.nexusrpc.ServiceDefinition;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.RetryOptions;
import io.temporal.common.SearchAttributeUpdate;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.Header;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.common.metadata.POJOWorkflowImplMetadata;
import io.temporal.common.metadata.POJOWorkflowInterfaceMetadata;
import io.temporal.common.metadata.POJOWorkflowMethodMetadata;
import io.temporal.internal.WorkflowThreadMarker;
import io.temporal.internal.common.ActivityOptionUtils;
import io.temporal.internal.common.NonIdempotentHandle;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.logging.ReplayAwareLogger;
import io.temporal.internal.statemachines.UnsupportedContinueAsNewRequest;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.workflow.*;
import io.temporal.workflow.Functions.Func;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Never reference directly. It is public only because Java doesn't have internal package support.
 */
public final class WorkflowInternal {
  public static final int DEFAULT_VERSION = -1;

  public static @Nonnull WorkflowThread newWorkflowMethodThread(Runnable runnable, String name) {
    Object workflowThread =
        currentThreadInternal()
            .getWorkflowContext()
            .getWorkflowInboundInterceptor()
            .newWorkflowMethodThread(runnable, name);
    Preconditions.checkState(
        workflowThread != null,
        "[BUG] One of the custom interceptors illegally overrode newWorkflowMethodThread result to null. "
            + "Check WorkflowInboundCallsInterceptor#newWorkflowMethodThread contract.");
    Preconditions.checkState(
        workflowThread instanceof WorkflowThread,
        "[BUG] One of the custom interceptors illegally overrode newWorkflowMethodThread result. "
            + "Check WorkflowInboundCallsInterceptor#newWorkflowMethodThread contract. "
            + "Illegal object returned from the interceptors chain: %s",
        workflowThread);
    return (WorkflowThread) workflowThread;
  }

  public static Promise<Void> newTimer(Duration duration) {
    assertNotReadOnly("schedule timer");
    return getWorkflowOutboundInterceptor().newTimer(duration);
  }

  /**
   * @param capacity the maximum size of the queue
   * @return new instance of {@link WorkflowQueue}
   * @deprecated this method created a deprecated implementation of the queue that has some methods
   *     implemented incorrectly. Please use {@link #newWorkflowQueue(int)} instead.
   */
  @Deprecated
  public static <E> WorkflowQueue<E> newQueue(int capacity) {
    return new WorkflowQueueDeprecatedImpl<>(capacity);
  }

  /**
   * Creates a {@link WorkflowQueue} implementation that can be used from workflow code.
   *
   * @param capacity the maximum size of the queue
   * @return new instance of {@link WorkflowQueue}
   */
  public static <E> WorkflowQueue<E> newWorkflowQueue(int capacity) {
    return new WorkflowQueueImpl<>(capacity);
  }

  public static WorkflowLock newWorkflowLock() {
    return new WorkflowLockImpl();
  }

  public static WorkflowSemaphore newWorkflowSemaphore(int permits) {
    return new WorkflowSemaphoreImpl(permits);
  }

  public static <E> CompletablePromise<E> newCompletablePromise() {
    return new CompletablePromiseImpl<>();
  }

  public static <E> Promise<E> newPromise(E value) {
    CompletablePromise<E> result = Workflow.newPromise();
    result.complete(value);
    return result;
  }

  public static <E> Promise<E> newFailedPromise(Exception failure) {
    CompletablePromise<E> result = new CompletablePromiseImpl<>();
    result.completeExceptionally(CheckedExceptionWrapper.wrap(failure));
    return result;
  }

  /**
   * Register query or queries implementation object. There is no need to register top level
   * workflow implementation object as it is done implicitly. Only methods annotated with @{@link
   * QueryMethod} are registered. TODO(quinn) LIES!
   */
  public static void registerListener(Object implementation) {
    if (implementation instanceof DynamicSignalHandler) {
      getWorkflowOutboundInterceptor()
          .registerDynamicSignalHandler(
              new WorkflowOutboundCallsInterceptor.RegisterDynamicSignalHandlerInput(
                  (DynamicSignalHandler) implementation));
      return;
    }
    if (implementation instanceof DynamicQueryHandler) {
      getWorkflowOutboundInterceptor()
          .registerDynamicQueryHandler(
              new WorkflowOutboundCallsInterceptor.RegisterDynamicQueryHandlerInput(
                  (DynamicQueryHandler) implementation));
      return;
    }
    if (implementation instanceof DynamicUpdateHandler) {
      getWorkflowOutboundInterceptor()
          .registerDynamicUpdateHandler(
              new WorkflowOutboundCallsInterceptor.RegisterDynamicUpdateHandlerInput(
                  (DynamicUpdateHandler) implementation));
      return;
    }
    Class<?> cls = implementation.getClass();
    POJOWorkflowImplMetadata workflowMetadata = POJOWorkflowImplMetadata.newListenerInstance(cls);
    for (POJOWorkflowMethodMetadata methodMetadata : workflowMetadata.getQueryMethods()) {
      Method method = methodMetadata.getWorkflowMethod();
      getWorkflowOutboundInterceptor()
          .registerQuery(
              new WorkflowOutboundCallsInterceptor.RegisterQueryInput(
                  methodMetadata.getName(),
                  method.getParameterTypes(),
                  method.getGenericParameterTypes(),
                  (args) -> {
                    try {
                      return method.invoke(implementation, args);
                    } catch (Throwable e) {
                      throw CheckedExceptionWrapper.wrap(e);
                    }
                  }));
    }
    List<WorkflowOutboundCallsInterceptor.SignalRegistrationRequest> requests = new ArrayList<>();
    for (POJOWorkflowMethodMetadata methodMetadata : workflowMetadata.getSignalMethods()) {
      Method method = methodMetadata.getWorkflowMethod();
      SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
      requests.add(
          new WorkflowOutboundCallsInterceptor.SignalRegistrationRequest(
              methodMetadata.getName(),
              signalMethod.unfinishedPolicy(),
              method.getParameterTypes(),
              method.getGenericParameterTypes(),
              (args) -> {
                try {
                  method.invoke(implementation, args);
                } catch (Throwable e) {
                  throw CheckedExceptionWrapper.wrap(e);
                }
              }));
    }
    if (!requests.isEmpty()) {
      getWorkflowOutboundInterceptor()
          .registerSignalHandlers(
              new WorkflowOutboundCallsInterceptor.RegisterSignalHandlersInput(requests));
    }

    // Get all validators and lazily assign them to update handlers as we see them.
    Map<String, POJOWorkflowMethodMetadata> validators =
        new HashMap<>(workflowMetadata.getUpdateValidatorMethods().size());
    for (POJOWorkflowMethodMetadata methodMetadata : workflowMetadata.getUpdateValidatorMethods()) {
      Method method = methodMetadata.getWorkflowMethod();
      UpdateValidatorMethod updateValidatorMethod =
          method.getAnnotation(UpdateValidatorMethod.class);
      if (validators.containsKey(updateValidatorMethod.updateName())) {
        throw new IllegalArgumentException(
            "Duplicate validator for update handle " + updateValidatorMethod.updateName());
      }
      validators.put(updateValidatorMethod.updateName(), methodMetadata);
    }

    List<WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest> updateRequests =
        new ArrayList<>();
    for (POJOWorkflowMethodMetadata methodMetadata : workflowMetadata.getUpdateMethods()) {
      Method method = methodMetadata.getWorkflowMethod();
      UpdateMethod updateMethod = method.getAnnotation(UpdateMethod.class);
      // Get the update name, defaulting to the method name if not specified.
      String updateMethodName = updateMethod.name();
      if (updateMethodName.isEmpty()) {
        updateMethodName = method.getName();
      }
      // Check if any validators claim they are the validator for this update
      POJOWorkflowMethodMetadata validatorMethodMetadata = validators.remove(updateMethodName);
      Method validatorMethod;
      if (validatorMethodMetadata != null) {
        validatorMethod = validatorMethodMetadata.getWorkflowMethod();
        if (!Arrays.equals(validatorMethod.getParameterTypes(), method.getParameterTypes())) {
          throw new IllegalArgumentException(
              "Validator for: "
                  + updateMethodName
                  + " type parameters do not match the update handle");
        }
      } else {
        validatorMethod = null;
      }
      updateRequests.add(
          new WorkflowOutboundCallsInterceptor.UpdateRegistrationRequest(
              methodMetadata.getName(),
              updateMethod.unfinishedPolicy(),
              method.getParameterTypes(),
              method.getGenericParameterTypes(),
              (args) -> {
                try {
                  if (validatorMethod != null) {
                    validatorMethod.invoke(implementation, args);
                  }
                } catch (Throwable e) {
                  throw CheckedExceptionWrapper.wrap(e);
                }
              },
              (args) -> {
                try {
                  return method.invoke(implementation, args);
                } catch (Throwable e) {
                  throw CheckedExceptionWrapper.wrap(e);
                }
              }));
    }
    if (!updateRequests.isEmpty()) {
      getWorkflowOutboundInterceptor()
          .registerUpdateHandlers(
              new WorkflowOutboundCallsInterceptor.RegisterUpdateHandlersInput(updateRequests));
    }
    if (!validators.isEmpty()) {
      throw new IllegalArgumentException(
          "Missing update methods for update validator(s): "
              + Joiner.on(", ").join(validators.keySet()));
    }
  }

  /** Should be used to get current time instead of {@link System#currentTimeMillis()} */
  public static long currentTimeMillis() {
    return getWorkflowOutboundInterceptor().currentTimeMillis();
  }

  public static void setDefaultActivityOptions(ActivityOptions activityOptions) {
    getRootWorkflowContext().setDefaultActivityOptions(activityOptions);
  }

  public static void applyActivityOptions(Map<String, ActivityOptions> activityTypeToOptions) {
    getRootWorkflowContext().applyActivityOptions(activityTypeToOptions);
  }

  public static void setDefaultLocalActivityOptions(LocalActivityOptions localActivityOptions) {
    getRootWorkflowContext().setDefaultLocalActivityOptions(localActivityOptions);
  }

  public static void applyLocalActivityOptions(
      Map<String, LocalActivityOptions> activityTypeToOptions) {
    getRootWorkflowContext().applyLocalActivityOptions(activityTypeToOptions);
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
    // Merge the activity options we may have received from the workflow with the options we may
    // have received in WorkflowImplementationOptions.
    SyncWorkflowContext context = getRootWorkflowContext();
    options = (options == null) ? context.getDefaultActivityOptions() : options;

    Map<String, ActivityOptions> mergedActivityOptionsMap;
    @Nonnull Map<String, ActivityOptions> predefinedActivityOptions = context.getActivityOptions();
    if (activityMethodOptions != null
        && !activityMethodOptions.isEmpty()
        && predefinedActivityOptions.isEmpty()) {
      // we need to merge only in this case
      mergedActivityOptionsMap = new HashMap<>(predefinedActivityOptions);
      ActivityOptionUtils.mergePredefinedActivityOptions(
          mergedActivityOptionsMap, activityMethodOptions);
    } else {
      mergedActivityOptionsMap =
          MoreObjects.firstNonNull(
              activityMethodOptions,
              MoreObjects.firstNonNull(predefinedActivityOptions, Collections.emptyMap()));
    }

    InvocationHandler invocationHandler =
        ActivityInvocationHandler.newInstance(
            activityInterface,
            options,
            mergedActivityOptionsMap,
            context.getWorkflowOutboundInterceptor(),
            () -> assertNotReadOnly("schedule activity"));
    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler);
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
      @Nullable Map<String, LocalActivityOptions> activityMethodOptions) {
    // Merge the activity options we may have received from the workflow with the options we may
    // have received in WorkflowImplementationOptions.
    SyncWorkflowContext context = getRootWorkflowContext();
    options = (options == null) ? context.getDefaultLocalActivityOptions() : options;

    Map<String, LocalActivityOptions> mergedLocalActivityOptionsMap;
    @Nonnull
    Map<String, LocalActivityOptions> predefinedLocalActivityOptions =
        context.getLocalActivityOptions();
    if (activityMethodOptions != null
        && !activityMethodOptions.isEmpty()
        && predefinedLocalActivityOptions.isEmpty()) {
      // we need to merge only in this case
      mergedLocalActivityOptionsMap = new HashMap<>(predefinedLocalActivityOptions);
      ActivityOptionUtils.mergePredefinedLocalActivityOptions(
          mergedLocalActivityOptionsMap, activityMethodOptions);
    } else {
      mergedLocalActivityOptionsMap =
          MoreObjects.firstNonNull(
              activityMethodOptions,
              MoreObjects.firstNonNull(predefinedLocalActivityOptions, Collections.emptyMap()));
    }

    InvocationHandler invocationHandler =
        LocalActivityInvocationHandler.newInstance(
            activityInterface,
            options,
            mergedLocalActivityOptionsMap,
            WorkflowInternal.getWorkflowOutboundInterceptor(),
            () -> assertNotReadOnly("schedule local activity"));
    return ActivityInvocationHandlerBase.newProxy(activityInterface, invocationHandler);
  }

  public static ActivityStub newUntypedActivityStub(ActivityOptions options) {
    return ActivityStubImpl.newInstance(
        options, getWorkflowOutboundInterceptor(), () -> assertNotReadOnly("schedule activity"));
  }

  public static ActivityStub newUntypedLocalActivityStub(LocalActivityOptions options) {
    return LocalActivityStubImpl.newInstance(
        options,
        getWorkflowOutboundInterceptor(),
        () -> assertNotReadOnly("schedule local activity"));
  }

  @SuppressWarnings("unchecked")
  public static <T> T newChildWorkflowStub(
      Class<T> workflowInterface, ChildWorkflowOptions options) {
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
            new Class<?>[] {workflowInterface, StubMarker.class, AsyncMarker.class},
            new ChildWorkflowInvocationHandler(
                workflowInterface,
                options,
                getWorkflowOutboundInterceptor(),
                WorkflowInternal::assertNotReadOnly));
  }

  @SuppressWarnings("unchecked")
  public static <T> T newExternalWorkflowStub(
      Class<T> workflowInterface, WorkflowExecution execution) {
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
            new Class<?>[] {workflowInterface, StubMarker.class, AsyncMarker.class},
            new ExternalWorkflowInvocationHandler(
                workflowInterface,
                execution,
                getWorkflowOutboundInterceptor(),
                WorkflowInternal::assertNotReadOnly));
  }

  public static Promise<WorkflowExecution> getWorkflowExecution(Object workflowStub) {
    if (workflowStub instanceof StubMarker) {
      Object stub = ((StubMarker) workflowStub).__getUntypedStub();
      return ((ChildWorkflowStub) stub).getExecution();
    }
    throw new IllegalArgumentException(
        "Not a workflow stub created through Workflow.newChildWorkflowStub: " + workflowStub);
  }

  public static ChildWorkflowStub newUntypedChildWorkflowStub(
      String workflowType, ChildWorkflowOptions options) {
    return new ChildWorkflowStubImpl(
        workflowType,
        options,
        getWorkflowOutboundInterceptor(),
        WorkflowInternal::assertNotReadOnly);
  }

  public static ExternalWorkflowStub newUntypedExternalWorkflowStub(WorkflowExecution execution) {
    return new ExternalWorkflowStubImpl(
        execution, getWorkflowOutboundInterceptor(), WorkflowInternal::assertNotReadOnly);
  }

  /**
   * Creates client stub that can be used to continue this workflow as new.
   *
   * @param workflowInterface interface type implemented by the next generation of workflow
   */
  @SuppressWarnings("unchecked")
  public static <T> T newContinueAsNewStub(
      Class<T> workflowInterface, ContinueAsNewOptions options) {
    return (T)
        Proxy.newProxyInstance(
            workflowInterface.getClassLoader(),
            new Class<?>[] {workflowInterface},
            new ContinueAsNewWorkflowInvocationHandler(
                workflowInterface, options, getWorkflowOutboundInterceptor()));
  }

  /**
   * Execute activity by name.
   *
   * @param name name of the activity
   * @param resultClass activity return type
   * @param args list of activity arguments
   * @param <R> activity return type
   * @return activity result
   */
  public static <R> R executeActivity(
      String name, ActivityOptions options, Class<R> resultClass, Type resultType, Object... args) {
    assertNotReadOnly("schedule activity");
    Promise<R> result =
        getWorkflowOutboundInterceptor()
            .executeActivity(
                new WorkflowOutboundCallsInterceptor.ActivityInput<>(
                    name, resultClass, resultType, args, options, Header.empty()))
            .getResult();
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return null; // ignored
    }
    return result.get();
  }

  public static void await(String reason, Supplier<Boolean> unblockCondition)
      throws DestroyWorkflowThreadError {
    assertNotReadOnly(reason);
    getWorkflowOutboundInterceptor().await(reason, unblockCondition);
  }

  public static boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition)
      throws DestroyWorkflowThreadError {
    assertNotReadOnly(reason);
    return getWorkflowOutboundInterceptor().await(timeout, reason, unblockCondition);
  }

  public static <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func) {
    assertNotReadOnly("side effect");
    return getWorkflowOutboundInterceptor().sideEffect(resultClass, resultType, func);
  }

  public static <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func) {
    assertNotReadOnly("mutable side effect");
    return getWorkflowOutboundInterceptor()
        .mutableSideEffect(id, resultClass, resultType, updated, func);
  }

  public static int getVersion(String changeId, int minSupported, int maxSupported) {
    assertNotReadOnly("get version");
    return getWorkflowOutboundInterceptor().getVersion(changeId, minSupported, maxSupported);
  }

  public static <V> Promise<Void> promiseAllOf(Iterable<Promise<V>> promises) {
    return new AllOfPromise(promises);
  }

  public static Promise<Void> promiseAllOf(Promise<?>... promises) {
    return new AllOfPromise(promises);
  }

  public static <V> Promise<V> promiseAnyOf(Iterable<Promise<V>> promises) {
    return CompletablePromiseImpl.promiseAnyOf(promises);
  }

  public static Promise<Object> promiseAnyOf(Promise<?>... promises) {
    return CompletablePromiseImpl.promiseAnyOf(promises);
  }

  public static CancellationScope newCancellationScope(boolean detached, Runnable runnable) {
    return new CancellationScopeImpl(detached, runnable);
  }

  public static CancellationScope newCancellationScope(
      boolean detached, Functions.Proc1<CancellationScope> proc) {
    return new CancellationScopeImpl(detached, proc);
  }

  public static CancellationScopeImpl currentCancellationScope() {
    return CancellationScopeImpl.current();
  }

  public static RuntimeException wrap(Throwable e) {
    return CheckedExceptionWrapper.wrap(e);
  }

  public static Throwable unwrap(Throwable e) {
    return CheckedExceptionWrapper.unwrap(e);
  }

  /** Returns false if not under workflow code. */
  public static boolean isReplaying() {
    Optional<WorkflowThread> thread = DeterministicRunnerImpl.currentThreadInternalIfPresent();
    return thread.isPresent() && getRootWorkflowContext().isReplaying();
  }

  public static <T> T getMemo(String key, Class<T> valueClass, Type genericType) {
    Payload memo = getRootWorkflowContext().getReplayContext().getMemo(key);
    if (memo == null) {
      return null;
    }

    return getDataConverterWithCurrentWorkflowContext().fromPayload(memo, valueClass, genericType);
  }

  public static <R> R retry(
      RetryOptions options, Optional<Duration> expiration, Functions.Func<R> fn) {
    assertNotReadOnly("retry");
    return WorkflowRetryerInternal.retry(
        options.toBuilder().validateBuildWithDefaults(), expiration, fn);
  }

  public static void continueAsNew(
      @Nullable String workflowType, @Nullable ContinueAsNewOptions options, Object[] args) {
    assertNotReadOnly("continue as new");
    assertNotInUpdateHandler("ContinueAsNew is not supported in an update handler");
    getWorkflowOutboundInterceptor()
        .continueAsNew(
            new WorkflowOutboundCallsInterceptor.ContinueAsNewInput(
                workflowType, options, args, Header.empty()));
  }

  public static void continueAsNew(
      @Nullable String workflowType,
      @Nullable ContinueAsNewOptions options,
      Object[] args,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor) {
    assertNotReadOnly("continue as new");
    assertNotInUpdateHandler("ContinueAsNew is not supported in an update handler");
    outboundCallsInterceptor.continueAsNew(
        new WorkflowOutboundCallsInterceptor.ContinueAsNewInput(
            workflowType, options, args, Header.empty()));
  }

  public static Promise<Void> cancelWorkflow(WorkflowExecution execution) {
    assertNotReadOnly("cancel workflow");
    return getWorkflowOutboundInterceptor()
        .cancelWorkflow(new WorkflowOutboundCallsInterceptor.CancelWorkflowInput(execution))
        .getResult();
  }

  public static void sleep(Duration duration) {
    assertNotReadOnly("sleep");
    getWorkflowOutboundInterceptor().sleep(duration);
  }

  public static boolean isWorkflowThread() {
    return WorkflowThreadMarker.isWorkflowThread();
  }

  public static <T> T deadlockDetectorOff(Functions.Func<T> func) {
    if (isWorkflowThread()) {
      try (NonIdempotentHandle ignored = getWorkflowThread().lockDeadlockDetector()) {
        return func.apply();
      }
    } else {
      return func.apply();
    }
  }

  public static WorkflowInfo getWorkflowInfo() {
    return new WorkflowInfoImpl(getRootWorkflowContext().getReplayContext());
  }

  public static Optional<UpdateInfo> getCurrentUpdateInfo() {
    return getRootWorkflowContext().getCurrentUpdateInfo();
  }

  public static Scope getMetricsScope() {
    return getRootWorkflowContext().getMetricsScope();
  }

  private static boolean isLoggingEnabledInReplay() {
    return getRootWorkflowContext().isLoggingEnabledInReplay();
  }

  public static UUID randomUUID() {
    assertNotReadOnly("random UUID");
    return getRootWorkflowContext().randomUUID();
  }

  public static Random newRandom() {
    assertNotReadOnly("random");
    return getRootWorkflowContext().newRandom();
  }

  public static Logger getLogger(Class<?> clazz) {
    Logger logger = LoggerFactory.getLogger(clazz);
    return new ReplayAwareLogger(
        logger, WorkflowInternal::isReplaying, WorkflowInternal::isLoggingEnabledInReplay);
  }

  public static Logger getLogger(String name) {
    Logger logger = LoggerFactory.getLogger(name);
    return new ReplayAwareLogger(
        logger, WorkflowInternal::isReplaying, WorkflowInternal::isLoggingEnabledInReplay);
  }

  public static <R> R getLastCompletionResult(Class<R> resultClass, Type resultType) {
    return getRootWorkflowContext().getLastCompletionResult(resultClass, resultType);
  }

  @Nullable
  public static <T> T getSearchAttribute(String name) {
    List<T> list = getSearchAttributeValues(name);
    if (list == null) {
      return null;
    }
    Preconditions.checkState(list.size() > 0);
    Preconditions.checkState(
        list.size() == 1,
        "search attribute with name '%s' contains a list '%s' of values instead of a single value",
        name,
        list);
    return list.get(0);
  }

  @Nullable
  public static <T> List<T> getSearchAttributeValues(String name) {
    SearchAttributes searchAttributes =
        getRootWorkflowContext().getReplayContext().getSearchAttributes();
    if (searchAttributes == null) {
      return null;
    }
    List<T> decoded = SearchAttributesUtil.decode(searchAttributes, name);
    return decoded != null ? Collections.unmodifiableList(decoded) : null;
  }

  @Nonnull
  public static Map<String, List<?>> getSearchAttributes() {
    SearchAttributes searchAttributes =
        getRootWorkflowContext().getReplayContext().getSearchAttributes();
    if (searchAttributes == null) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(SearchAttributesUtil.decode(searchAttributes));
  }

  @Nonnull
  public static io.temporal.common.SearchAttributes getTypedSearchAttributes() {
    SearchAttributes searchAttributes =
        getRootWorkflowContext().getReplayContext().getSearchAttributes();
    return SearchAttributesUtil.decodeTyped(searchAttributes);
  }

  public static void upsertSearchAttributes(Map<String, ?> searchAttributes) {
    assertNotReadOnly("upsert search attribute");
    getWorkflowOutboundInterceptor().upsertSearchAttributes(searchAttributes);
  }

  public static void upsertTypedSearchAttributes(
      SearchAttributeUpdate<?>... searchAttributeUpdates) {
    assertNotReadOnly("upsert search attribute");
    getWorkflowOutboundInterceptor().upsertTypedSearchAttributes(searchAttributeUpdates);
  }

  public static void upsertMemo(Map<String, Object> memo) {
    assertNotReadOnly("upsert memo");
    getWorkflowOutboundInterceptor().upsertMemo(memo);
  }

  public static DataConverter getDataConverter() {
    return getRootWorkflowContext().getDataConverter();
  }

  static DataConverter getDataConverterWithCurrentWorkflowContext() {
    return getRootWorkflowContext().getDataConverterWithCurrentWorkflowContext();
  }

  /**
   * Name of the workflow type the interface defines. It is either the interface short name * or
   * value of {@link WorkflowMethod#name()} parameter.
   *
   * @param workflowInterfaceClass interface annotated with @WorkflowInterface
   */
  public static String getWorkflowType(Class<?> workflowInterfaceClass) {
    POJOWorkflowInterfaceMetadata metadata =
        POJOWorkflowInterfaceMetadata.newInstance(workflowInterfaceClass);
    return metadata.getWorkflowType().get();
  }

  public static Optional<Exception> getPreviousRunFailure() {
    return Optional.ofNullable(getRootWorkflowContext().getReplayContext().getPreviousRunFailure())
        // Temporal Failure Values are additional user payload and serialized using user data
        // converter
        .map(f -> getDataConverterWithCurrentWorkflowContext().failureToException(f));
  }

  public static boolean isEveryHandlerFinished() {
    return getRootWorkflowContext().isEveryHandlerFinished();
  }

  public static <T> T newNexusServiceStub(Class<T> serviceInterface, NexusServiceOptions options) {
    SyncWorkflowContext context = getRootWorkflowContext();
    NexusServiceOptions baseOptions =
        (options == null) ? context.getDefaultNexusServiceOptions() : options;

    @Nonnull
    Map<String, NexusServiceOptions> predefinedNexusServiceOptions =
        context.getNexusServiceOptions();

    ServiceDefinition serviceDef = ServiceDefinition.fromClass(serviceInterface);
    NexusServiceOptions mergedOptions =
        NexusServiceOptions.newBuilder(predefinedNexusServiceOptions.get(serviceDef.getName()))
            .mergeNexusServiceOptions(baseOptions)
            .build();
    return (T)
        Proxy.newProxyInstance(
            serviceInterface.getClassLoader(),
            new Class<?>[] {serviceInterface, StubMarker.class, AsyncInternal.AsyncMarker.class},
            new NexusServiceInvocationHandler(
                serviceDef,
                mergedOptions,
                getWorkflowOutboundInterceptor(),
                WorkflowInternal::assertNotReadOnly));
  }

  public static NexusServiceStub newUntypedNexusServiceStub(
      String service, NexusServiceOptions options) {
    return new NexusServiceStubImpl(
        service, options, getWorkflowOutboundInterceptor(), WorkflowInternal::assertNotReadOnly);
  }

  public static <T, R> NexusOperationHandle<R> startNexusOperation(
      Functions.Func1<T, R> operation, T arg) {
    return StartNexusCallInternal.startNexusOperation(() -> operation.apply(arg));
  }

  public static <R> NexusOperationHandle<R> startNexusOperation(Functions.Func<R> operation) {
    return StartNexusCallInternal.startNexusOperation(() -> operation.apply());
  }

  static WorkflowOutboundCallsInterceptor getWorkflowOutboundInterceptor() {
    return getRootWorkflowContext().getWorkflowOutboundInterceptor();
  }

  static SyncWorkflowContext getRootWorkflowContext() {
    return DeterministicRunnerImpl.currentThreadInternal().getWorkflowContext();
  }

  static boolean isReadOnly() {
    return getRootWorkflowContext().isReadOnly();
  }

  static void assertNotReadOnly(String action) {
    if (isReadOnly()) {
      throw new ReadOnlyException(action);
    }
  }

  static void assertNotInUpdateHandler(String message) {
    if (getCurrentUpdateInfo().isPresent()) {
      throw new UnsupportedContinueAsNewRequest(message);
    }
  }

  private static WorkflowThread getWorkflowThread() {
    return DeterministicRunnerImpl.currentThreadInternal();
  }

  /** Prohibit instantiation */
  private WorkflowInternal() {}
}
