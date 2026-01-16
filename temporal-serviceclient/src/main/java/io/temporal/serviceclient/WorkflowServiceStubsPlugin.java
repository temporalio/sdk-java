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

package io.temporal.serviceclient;

/**
 * Plugin interface for customizing Temporal workflow service stubs configuration and connection.
 *
 * <p>This is a low-level plugin interface for configuring the gRPC connection to the Temporal
 * server. For most use cases, use {@code WorkflowClientPlugin} or {@code WorkerPlugin} in the
 * temporal-sdk module instead.
 *
 * <p>Plugins that implement both {@code WorkflowServiceStubsPlugin} and {@code
 * WorkflowClientPlugin} are automatically propagated from the service stubs to the workflow client.
 *
 * @see ServiceStubsPlugin
 */
public interface WorkflowServiceStubsPlugin
    extends ServiceStubsPlugin<
        WorkflowServiceStubsOptions, WorkflowServiceStubsOptions.Builder, WorkflowServiceStubs> {}
