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

package io.temporal.internal.sync;

import com.uber.m3.tally.NoopScope;
import com.uber.m3.tally.Scope;
import io.temporal.api.command.v1.ContinueAsNewWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.SignalExternalWorkflowExecutionCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.common.v1.WorkflowType;
import io.temporal.api.failure.v1.Failure;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.replay.ExecuteActivityParameters;
import io.temporal.internal.replay.ExecuteLocalActivityParameters;
import io.temporal.internal.replay.ReplayWorkflowContext;
import io.temporal.internal.replay.StartChildWorkflowExecutionParameters;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.*;

public class DummySyncWorkflowContext {
  public static SyncWorkflowContext newDummySyncWorkflowContext() {
    SyncWorkflowContext context =
        new SyncWorkflowContext(
            new DummyReplayWorkflowContext(), DataConverter.getDefaultInstance(), null, null, null);
    context.initHeadOutboundCallsInterceptor(context);
    context.initHeadInboundCallsInterceptor(
        new BaseRootWorkflowInboundCallsInterceptor(context) {
          @Override
          public WorkflowOutput execute(WorkflowInput input) {
            throw new UnsupportedOperationException(
                "#execute is not implemented or needed for low level DeterministicRunner tests");
          }
        });
    return context;
  }

  private static final class DummyReplayWorkflowContext implements ReplayWorkflowContext {

    private final Timer timer = new Timer();

    @Override
    public WorkflowExecution getWorkflowExecution() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WorkflowExecution getParentWorkflowExecution() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public WorkflowType getWorkflowType() {
      return WorkflowType.newBuilder().setName("dummy-workflow").build();
    }

    @Override
    public boolean isCancelRequested() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public ContinueAsNewWorkflowExecutionCommandAttributes getContinueAsNewOnCompletion() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Optional<String> getContinuedExecutionRunId() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public String getTaskQueue() {
      return "dummy-task-queue";
    }

    @Override
    public String getNamespace() {
      return "dummy-namespace";
    }

    @Override
    public String getWorkflowId() {
      return "dummy-workflow-id";
    }

    @Override
    public String getRunId() {
      return "dummy-run-id";
    }

    @Override
    public Duration getWorkflowRunTimeout() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Duration getWorkflowExecutionTimeout() {
      return Duration.ZERO;
    }

    @Override
    public long getRunStartedTimestampMillis() {
      return 0;
    }

    @Override
    public Duration getWorkflowTaskTimeout() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Payload getMemo(String key) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public SearchAttributes getSearchAttributes() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Map<String, Object> getPropagatedContexts() {
      return null;
    }

    @Override
    public List<ContextPropagator> getContextPropagators() {
      return null;
    }

    @Override
    public Functions.Proc1<Exception> scheduleActivityTask(
        ExecuteActivityParameters parameters,
        Functions.Proc2<Optional<Payloads>, Failure> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Functions.Proc scheduleLocalActivityTask(
        ExecuteLocalActivityParameters parameters,
        Functions.Proc2<Optional<Payloads>, Failure> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Functions.Proc1<Exception> startChildWorkflow(
        StartChildWorkflowExecutionParameters parameters,
        Functions.Proc1<WorkflowExecution> executionCallback,
        Functions.Proc2<Optional<Payloads>, Exception> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Functions.Proc1<Exception> signalExternalWorkflowExecution(
        SignalExternalWorkflowExecutionCommandAttributes.Builder attributes,
        Functions.Proc2<Void, Failure> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void requestCancelExternalWorkflowExecution(
        WorkflowExecution execution, Functions.Proc2<Void, RuntimeException> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void continueAsNewOnCompletion(
        ContinueAsNewWorkflowExecutionCommandAttributes attributes) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }

    @Override
    public Functions.Proc1<RuntimeException> newTimer(
        Duration delay, Functions.Proc1<RuntimeException> callback) {
      timer.schedule(
          new TimerTask() {
            @Override
            public void run() {
              callback.apply(null);
            }
          },
          delay.toMillis());
      return (e) -> {
        callback.apply(new CanceledFailure(null));
      };
    }

    @Override
    public void sideEffect(
        Functions.Func<Optional<Payloads>> func, Functions.Proc1<Optional<Payloads>> callback) {
      callback.apply(func.apply());
    }

    @Override
    public void mutableSideEffect(
        String id,
        Functions.Func1<Optional<Payloads>, Optional<Payloads>> func,
        Functions.Proc1<Optional<Payloads>> callback) {
      callback.apply(func.apply(Optional.empty()));
    }

    @Override
    public boolean isReplaying() {
      return false;
    }

    @Override
    public void getVersion(
        String changeId, int minSupported, int maxSupported, Functions.Proc1<Integer> callback) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Random newRandom() {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public Scope getMetricsScope() {
      return new NoopScope();
    }

    @Override
    public boolean getEnableLoggingInReplay() {
      return false;
    }

    @Override
    public UUID randomUUID() {
      return UUID.randomUUID();
    }

    @Override
    public void upsertSearchAttributes(SearchAttributes searchAttributes) {
      throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public int getAttempt() {
      return 1;
    }
  }
}
