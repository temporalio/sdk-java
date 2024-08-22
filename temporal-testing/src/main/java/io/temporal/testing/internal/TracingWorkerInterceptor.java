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

package io.temporal.testing.internal;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCompletionException;
import io.temporal.common.SearchAttributeUpdate;
import io.temporal.common.interceptors.*;
import io.temporal.internal.sync.WorkflowMethodThreadNameStrategy;
import io.temporal.workflow.Functions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TracingWorkerInterceptor implements WorkerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(TracingWorkerInterceptor.class);

  private final FilteredTrace trace;
  private List<String> expected;

  public TracingWorkerInterceptor(FilteredTrace trace) {
    this.trace = trace;
  }

  public String getTrace() {
    return String.join("\n", trace.getImpl());
  }

  public void setExpected(String... expected) {
    this.expected = Arrays.asList(expected);
  }

  public void assertExpected() {
    // As it stands, when the trace is empty but the expected list isn't this still passes.
    if (expected != null) {
      List<String> traceElements = trace.getImpl();
      for (int i = 0; i < traceElements.size(); i++) {
        String t = traceElements.get(i);
        String expectedRegExp;
        if (expected.size() <= i) {
          expectedRegExp = "";
        } else {
          expectedRegExp = expected.get(i);
        }
        assertTrue(
            t
                + " doesn't match "
                + expectedRegExp
                + ": \n expected=\n"
                + String.join("\n", expected)
                + "\n actual=\n"
                + String.join("\n", traceElements)
                + "\n",
            t.matches(expectedRegExp));
      }
    }
  }

  @Override
  public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
    if (!WorkflowUnsafe.isReplaying()) {
      trace.add("interceptExecuteWorkflow " + Workflow.getInfo().getWorkflowId());
    }
    return new WorkflowInboundCallsInterceptorBase(next) {
      @Override
      public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
        next.init(new TracingWorkflowOutboundCallsInterceptor(trace, outboundCalls));
      }

      @Override
      public void handleSignal(SignalInput input) {
        trace.add("handleSignal " + input.getSignalName());
        super.handleSignal(input);
      }

      @Override
      public QueryOutput handleQuery(QueryInput input) {
        trace.add("handleQuery " + input.getQueryName());
        return super.handleQuery(input);
      }

      @Nonnull
      @Override
      public Object newWorkflowMethodThread(Runnable runnable, String name) {
        if (!WorkflowUnsafe.isReplaying()) {
          if (name.startsWith(WorkflowMethodThreadNameStrategy.WORKFLOW_MAIN_THREAD_PREFIX)) {
            // strip the IDs we add to identify WF thread method
            trace.add("newThread " + WorkflowMethodThreadNameStrategy.WORKFLOW_MAIN_THREAD_PREFIX);
          } else {
            trace.add("newThread " + name);
          }
        }
        return next.newWorkflowMethodThread(runnable, name);
      }
    };
  }

  @Override
  public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
    return new TracingActivityInboundCallsInterceptor(trace, next);
  }

  public static class FilteredTrace {

    private final List<String> impl = Collections.synchronizedList(new ArrayList<>());

    public boolean add(String s) {
      log.trace("FilteredTrace isReplaying=" + WorkflowUnsafe.isReplaying());
      if (!WorkflowUnsafe.isReplaying()) {
        return impl.add(s);
      }
      return true;
    }

    List<String> getImpl() {
      return impl;
    }
  }

  private static class TracingWorkflowOutboundCallsInterceptor
      implements WorkflowOutboundCallsInterceptor {

    private final FilteredTrace trace;
    private final WorkflowOutboundCallsInterceptor next;

    private TracingWorkflowOutboundCallsInterceptor(
        FilteredTrace trace, WorkflowOutboundCallsInterceptor next) {
      WorkflowInfo workflowInfo =
          Workflow.getInfo(); // checks that info is available in the constructor
      assertNotNull(workflowInfo);
      this.trace = trace;
      this.next = Objects.requireNonNull(next);
    }

    @Override
    public <R> ActivityOutput<R> executeActivity(ActivityInput<R> input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("executeActivity " + input.getActivityName());
      }
      return next.executeActivity(input);
    }

    @Override
    public <R> LocalActivityOutput<R> executeLocalActivity(LocalActivityInput<R> input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("executeLocalActivity " + input.getActivityName());
      }
      return next.executeLocalActivity(input);
    }

    @Override
    public <R> ChildWorkflowOutput<R> executeChildWorkflow(ChildWorkflowInput<R> input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("executeChildWorkflow " + input.getWorkflowType());
      }
      return next.executeChildWorkflow(input);
    }

    @Override
    public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
        ExecuteNexusOperationInput<R> input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("executeNexusOperation " + input.getOperation());
      }
      return next.executeNexusOperation(input);
    }

    @Override
    public Random newRandom() {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("newRandom");
      }
      return next.newRandom();
    }

    @Override
    public SignalExternalOutput signalExternalWorkflow(SignalExternalInput input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add(
            "signalExternalWorkflow "
                + input.getExecution().getWorkflowId()
                + " "
                + input.getSignalName());
      }
      return next.signalExternalWorkflow(input);
    }

    @Override
    public CancelWorkflowOutput cancelWorkflow(CancelWorkflowInput input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("cancelWorkflow " + input.getExecution().getWorkflowId());
      }
      return next.cancelWorkflow(input);
    }

    @Override
    public void sleep(Duration duration) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("sleep " + duration);
      }
      next.sleep(duration);
    }

    @Override
    public boolean await(Duration timeout, String reason, Supplier<Boolean> unblockCondition) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("await " + timeout + " " + reason);
      }
      return next.await(timeout, reason, unblockCondition);
    }

    @Override
    public void await(String reason, Supplier<Boolean> unblockCondition) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("await " + reason);
      }
      next.await(reason, unblockCondition);
    }

    @Override
    public Promise<Void> newTimer(Duration duration) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("newTimer " + duration);
      }
      return next.newTimer(duration);
    }

    @Override
    public <R> R sideEffect(Class<R> resultClass, Type resultType, Functions.Func<R> func) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("sideEffect");
      }
      return next.sideEffect(resultClass, resultType, func);
    }

    @Override
    public <R> R mutableSideEffect(
        String id,
        Class<R> resultClass,
        Type resultType,
        BiPredicate<R, R> updated,
        Functions.Func<R> func) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("mutableSideEffect");
      }
      return next.mutableSideEffect(id, resultClass, resultType, updated, func);
    }

    @Override
    public int getVersion(String changeId, int minSupported, int maxSupported) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("getVersion");
      }
      return next.getVersion(changeId, minSupported, maxSupported);
    }

    @Override
    public void continueAsNew(ContinueAsNewInput input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("continueAsNew");
      }
      next.continueAsNew(input);
    }

    @Override
    public void registerQuery(RegisterQueryInput input) {
      String queryType = input.getQueryType();
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("registerQuery " + queryType);
      }
      next.registerQuery(
          new RegisterQueryInput(
              queryType,
              input.getArgTypes(),
              input.getGenericArgTypes(),
              (args) -> {
                Object result = input.getCallback().apply(args);
                if (!WorkflowUnsafe.isReplaying()) {
                  if (queryType.equals("query")) {
                    log.trace("query", new Throwable());
                  }
                  trace.add("query " + queryType);
                }
                return result;
              }));
    }

    @Override
    public void registerSignalHandlers(RegisterSignalHandlersInput input) {
      if (!WorkflowUnsafe.isReplaying()) {
        StringBuilder signals = new StringBuilder();
        for (SignalRegistrationRequest request : input.getRequests()) {
          if (signals.length() > 0) {
            signals.append(", ");
          }
          signals.append(request.getSignalType());
        }
        trace.add("registerSignalHandlers " + signals);
      }
      next.registerSignalHandlers(input);
    }

    @Override
    public void registerUpdateHandlers(RegisterUpdateHandlersInput input) {
      if (!WorkflowUnsafe.isReplaying()) {
        StringBuilder updates = new StringBuilder();
        for (UpdateRegistrationRequest request : input.getRequests()) {
          if (updates.length() > 0) {
            updates.append(", ");
          }
          updates.append(request.getUpdateName());
        }
        trace.add("registerUpdateHandlers " + updates);
      }
      next.registerUpdateHandlers(input);
    }

    @Override
    public void registerDynamicUpdateHandler(RegisterDynamicUpdateHandlerInput input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("registerDynamicUpdateHandler");
      }
      next.registerDynamicUpdateHandler(input);
    }

    @Override
    public void registerDynamicSignalHandler(RegisterDynamicSignalHandlerInput input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("registerDynamicSignalHandler");
      }
      next.registerDynamicSignalHandler(input);
    }

    @Override
    public void registerDynamicQueryHandler(RegisterDynamicQueryHandlerInput input) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("registerDynamicQueryHandler");
      }
      next.registerDynamicQueryHandler(input);
    }

    @Override
    public UUID randomUUID() {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("randomUUID");
      }
      return next.randomUUID();
    }

    @Override
    public void upsertSearchAttributes(Map<String, ?> searchAttributes) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("upsertSearchAttributes");
      }
      next.upsertSearchAttributes(searchAttributes);
    }

    @Override
    public void upsertTypedSearchAttributes(SearchAttributeUpdate<?>... searchAttributeUpdates) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("upsertTypedSearchAttributes");
      }
      next.upsertTypedSearchAttributes(searchAttributeUpdates);
    }

    @Override
    public void upsertMemo(Map<String, Object> memo) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("upsertMemo");
      }
      next.upsertMemo(memo);
    }

    @Override
    public Object newChildThread(Runnable runnable, boolean detached, String name) {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("newThread " + name);
      }
      return next.newChildThread(runnable, detached, name);
    }

    @Override
    public long currentTimeMillis() {
      if (!WorkflowUnsafe.isReplaying()) {
        trace.add("currentTimeMillis");
      }
      return next.currentTimeMillis();
    }
  }

  private static class TracingActivityInboundCallsInterceptor
      implements ActivityInboundCallsInterceptor {

    private final FilteredTrace trace;
    private final ActivityInboundCallsInterceptor next;
    private String type;
    private boolean local;

    public TracingActivityInboundCallsInterceptor(
        FilteredTrace trace, ActivityInboundCallsInterceptor next) {
      this.trace = trace;
      this.next = next;
    }

    @Override
    public void init(ActivityExecutionContext context) {
      this.type = context.getInfo().getActivityType();
      this.local = context.getInfo().isLocal();
      next.init(
          new ActivityExecutionContextBase(context) {
            @Override
            public <V> void heartbeat(V details) throws ActivityCompletionException {
              trace.add("heartbeat " + details);
              super.heartbeat(details);
            }
          });
    }

    @Override
    public ActivityOutput execute(ActivityInput input) {
      trace.add((local ? "local " : "") + "activity " + type);
      return next.execute(input);
    }
  }
}
