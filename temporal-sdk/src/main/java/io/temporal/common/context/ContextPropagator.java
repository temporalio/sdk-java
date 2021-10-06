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

import io.temporal.api.common.v1.Payload;
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
 *   public String getName() {
 *     return this.getClass().getName();
 *   }
 *
 *   public Object getCurrentContext() {
 *     Map<String, String> context = new HashMap<>();
 *     for (Map.Entry<String, String> entry : MDC.getCopyOfContextMap().entrySet()) {
 *       if (entry.getKey().startsWith("X-")) {
 *         context.put(entry.getKey(), entry.getValue());
 *       }
 *     }
 *     return context;
 *   }
 *
 *   public void setCurrentContext(Object context) {
 *     Map<String, String> contextMap = (Map<String, String>) context;
 *     for (Map.Entry<String, String> entry : contextMap.entrySet()) {
 *       MDC.put(entry.getKey(), entry.getValue());
 *     }
 *   }
 *
 *   public Map<String, Payload> serializeContext(Object context) {
 *     Map<String, String> contextMap = (Map<String, String>) context;
 *     Map<String, Payload> serializedContext = new HashMap<>();
 *     for (Map.Entry<String, String> entry : contextMap.entrySet()) {
 *       serializedContext.put(entry.getKey(), DataConverter.getDefaultInstance().toPayload(entry.getValue()).get());
 *     }
 *     return serializedContext;
 *   }
 *
 *   public Object deserializeContext(Map<String, Payload> context) {
 *     Map<String, String> contextMap = new HashMap<>();
 *     for (Map.Entry<String, Payload> entry : context.entrySet()) {
 *       contextMap.put(entry.getKey(), DataConverter.getDefaultInstance().fromPayload(entry.getValue(), String.class, String.class));
 *     }
 *     return contextMap;
 *   }
 * }
 * }</pre>
 *
 * To set up the context propagators, you must configure them during creation of WorkflowClient on
 * both client/stubs and worker side: <br>
 *
 * <pre>{@code
 * WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
 *                 .setContextPropagators(Collections.singletonList(new MDCContextPropagator()))
 *                 .build();
 * WorkflowClient client = WorkflowClient.newInstance(service, factoryOptions);
 *
 * Workflow workflow = client.newWorkflowStub(Workflow.class,
 *                WorkflowOptions.newBuilder().setTaskQueue("taskQueue").build());
 * //or
 * WorkerFactory workerFactory = WorkerFactory.newInstance(client);
 * }</pre>
 *
 * <br>
 * If you wish to override them for a workflow stub or a child workflow, you can do so when creating
 * a <br>
 * {@link io.temporal.client.WorkflowStub}:
 *
 * <pre>{@code
 * Workflow workflow = client.newWorkflowStub(Workflow.class,
 *                WorkflowOptions.newBuilder()
 *                        //...
 *                        .setContextPropagators(Collections.singletonList(new MDCContextPropagator()))
 *                        .build());
 * }</pre>
 *
 * <br>
 * {@link io.temporal.workflow.ChildWorkflowStub}:
 *
 * <pre>{@code
 * ChildWorkflow childWorkflow = Workflow.newChildWorkflowStub(ChildWorkflow.class,
 *                ChildWorkflowOptions.newBuilder()
 *                        .setContextPropagators(Collections.singletonList(new MDCContextPropagator()))
 *                        .build());
 * }</pre>
 *
 * <br>
 */
public interface ContextPropagator {

  /**
   * Returns the unique name of the context propagator. This name is used to store and address
   * context objects to pass them between threads. This name is not used for serialization,
   * serialization is fully controlled by {@link #serializeContext(Object)}
   *
   * @return The unique name of this propagator.
   */
  String getName();

  /**
   * Given context data, serialize it for transmission in the RPC header.
   *
   * <p>Note: keys of the result map should have unique values across all propagators and
   * interceptors, because they all share the same single keyspace of a single instance of {@code
   * temporal.api.common.v1.Header}
   *
   * @return serialized representation to be sent in request {@code temporal.api.common.v1.Header}
   */
  Map<String, Payload> serializeContext(Object context);

  /** Turn the serialized header data into context object(s) */
  Object deserializeContext(Map<String, Payload> header);

  /** Returns the current context in object form */
  Object getCurrentContext();

  /** Sets the current context */
  void setCurrentContext(Object context);
}
