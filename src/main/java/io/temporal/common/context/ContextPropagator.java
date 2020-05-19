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

package io.temporal.common.context;

import io.temporal.proto.common.Payload;
import java.util.Map;

/**
 * Context Propagators are used to propagate information from workflow to activity, workflow to
 * child workflow, and workflow to child thread (using {@link io.temporal.workflow.Async}).
 *
 * <p>A sample <code>ContextPropagator</code> that copies all {@link org.slf4j.MDC} entries starting
 * with a given prefix along the code path looks like this:
 *
 * <pre>{@code
 * public class MDCContextPropagator implements ContextPropagator {
 *
 *     public String getName() {
 *         return this.getClass().getName();
 *     }
 *
 *     public Object getCurrentContext() {
 *         Map<String, String> context = new HashMap<>();
 *         for (Map.Entry<String, String> entry : MDC.getCopyOfContextMap().entrySet()) {
 *             if (entry.getKey().startsWith("X-")) {
 *                 context.put(entry.getKey(), entry.getValue());
 *             }
 *         }
 *         return context;
 *     }
 *
 *     public void setCurrentContext(Object context) {
 *         Map<String, String> contextMap = (Map<String, String>)context;
 *         for (Map.Entry<String, String> entry : contextMap.entrySet()) {
 *             MDC.put(entry.getKey(), entry.getValue());
 *         }
 *     }
 *
 *     public Map<String, byte[]> serializeContext(Object context) {
 *         Map<String, String> contextMap = (Map<String, String>)context;
 *         Map<String, byte[]> serializedContext = new HashMap<>();
 *         for (Map.Entry<String, String> entry : contextMap.entrySet()) {
 *             serializedContext.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
 *         }
 *         return serializedContext;
 *     }
 *
 *     public Object deserializeContext(Map<String, Payload> context) {
 *         Map<String, String> contextMap = new HashMap<>();
 *         for (Map.Entry<String, byte[]> entry : context.entrySet()) {
 *             contextMap.put(entry.getKey(), new String(entry.getValue(), StandardCharsets.UTF_8));
 *         }
 *         return contextMap;
 *     }
 * }
 * }</pre>
 *
 * To set up the context propagators, you must configure them when: <br>
 * Creating WorkflowClient:
 *
 * <pre>{@code
 * WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
 *                 .setContextPropagators(Collections.singletonList(new MDCContextPropagator()))
 *                 .build();
 * WorkflowClient client = WorkflowClient.newInstance(service, factoryOptions);
 * }</pre>
 *
 * <br>
 * If you want to have override the {@code ContextPropagator} instances for your activities, you can
 * specify them at the {@link io.temporal.activity.ActivityOptions} level like so:
 *
 * <pre>{@code
 * activities = Workflow.newActivityStub(Activity.class,
 *                 ActivityOptions.newBuilder()
 *                         .setScheduleToCloseTimeout(Duration.ofSeconds(60))
 *                         .setContextPropagators(Collections.singletonList(new MDCContextPropagator()))
 *                         .build());
 * }</pre>
 *
 * <br>
 * And similarly, if you wish to override them for child workflows, you can do so when creating a
 * {@link io.temporal.workflow.ChildWorkflowStub}:
 *
 * <pre>{@code
 * ChildWorkflow childWorkflow = Workflow.newChildWorkflowStub(ChildWorkflow.class,
 *                ChildWorkflowOptions.newBuilder()
 *                        .setContextPropagators(Collections.singletonList(new MDCContextPropagator()))
 *                        .build());
 * }</pre>
 */
public interface ContextPropagator {

  /**
   * Returns the name of the context propagator (for use in serialization and transfer).
   * ContextPropagators will only be given context information
   *
   * @return The name of this propagator
   */
  String getName();

  /** Given context data, serialize it for transmission in the RPC header */
  Map<String, Payload> serializeContext(Object context);

  /** Turn the serialized header data into context object(s) */
  Object deserializeContext(Map<String, Payload> context);

  /** Returns the current context in object form */
  Object getCurrentContext();

  /** Sets the current context */
  void setCurrentContext(Object context);
}
