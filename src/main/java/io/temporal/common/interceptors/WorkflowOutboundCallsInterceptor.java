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

package io.temporal.common.interceptors;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Functions.Func;
import io.temporal.workflow.Promise;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Can be used to intercept workflow code calls to the Temporal APIs. An instance should be created
 * through {@link WorkflowInterceptor#interceptWorkflow(WorkflowInboundCallsInterceptor)}. An
 * interceptor instance must forward all the calls to the next interceptor passed to the
 * interceptExecuteWorkflow call.
 *
 * <p>The calls to the interceptor are executed in the context of a workflow and must follow the
 * same rules all the other workflow code follows.
 */
public interface WorkflowOutboundCallsInterceptor {

  final class WorkflowResult<R> {

    private final Promise<R> result;
    private final Promise<WorkflowExecution> workflowExecution;

    public WorkflowResult(Promise<R> result, Promise<WorkflowExecution> workflowExecution) {
      this.result = result;
      this.workflowExecution = workflowExecution;
    }

    public Promise<R> getResult() {
      return result;
    }

    public Promise<WorkflowExecution> getWorkflowExecution() {
      return workflowExecution;
    }
  }

  <R> Promise<R> executeActivity(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      Object[] args,
      ActivityOptions options);

  <R> Promise<R> executeLocalActivity(
      String activityName,
      Class<R> resultClass,
      Type resultType,
      Object[] args,
      LocalActivityOptions options);

  <R> WorkflowResult<R> executeChildWorkflow(
      String workflowType,
      Class<R> resultClass,
      Type resultType,
      Object[] args,
      ChildWorkflowOptions options);

  Random newRandom();

  Promise<Void> signalExternalWorkflow(
      WorkflowExecution execution, String signalName, Object[] args);

  Promise<Void> cancelWorkflow(WorkflowExecution execution);

  void sleep(Duration duration);

  boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition);

  void await(String reason, Supplier<Boolean> unblockCondition);

  Promise<Void> newTimer(Duration duration);

  <R> R sideEffect(Class<R> resultClass, Type resultType, Func<R> func);

  <R> R mutableSideEffect(
      String id, Class<R> resultClass, Type resultType, BiPredicate<R, R> updated, Func<R> func);

  int getVersion(String changeId, int minSupported, int maxSupported);

  void continueAsNew(
      Optional<String> workflowType, Optional<ContinueAsNewOptions> options, Object[] args);

  void registerQuery(
      String queryType,
      Class<?>[] argTypes,
      Type[] genericArgTypes,
      Functions.Func1<Object[], Object> callback);

  void registerSignal(
      String signalType,
      Class<?>[] argTypes,
      Type[] genericArgTypes,
      Functions.Proc1<Object[]> callback);

  UUID randomUUID();

  void upsertSearchAttributes(Map<String, Object> searchAttributes);

  Object newThread(Runnable runnable, boolean detached, String name);

  long currentTimeMillis();
}
