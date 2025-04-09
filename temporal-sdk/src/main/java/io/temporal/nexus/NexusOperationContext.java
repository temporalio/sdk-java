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

package io.temporal.nexus;

import com.uber.m3.tally.Scope;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

/**
 * Context object passed to a Nexus operation implementation. Use {@link
 * Nexus#getOperationContext()} from a Nexus Operation implementation to access.
 */
public interface NexusOperationContext {

  /**
   * Get scope for reporting business metrics in a nexus handler. This scope is tagged with the
   * service and operation.
   *
   * <p>The original metrics scope is set through {@link
   * WorkflowServiceStubsOptions.Builder#setMetricsScope(Scope)} when a worker starts up.
   */
  Scope getMetricsScope();

  /**
   * Get a {@link WorkflowClient} that can be used to start interact with the Temporal service from
   * a Nexus handler.
   */
  WorkflowClient getWorkflowClient();
}
