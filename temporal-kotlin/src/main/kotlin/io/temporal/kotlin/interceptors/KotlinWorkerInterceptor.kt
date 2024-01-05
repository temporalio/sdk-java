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

package io.temporal.kotlin.interceptors

import io.temporal.common.Experimental
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor

/**
 * Intercepts workflow and activity executions.
 *
 *
 * Prefer extending [WorkerInterceptorBase] and overriding only the methods you need
 * instead of implementing this interface directly. [WorkerInterceptorBase] provides correct
 * default implementations to all the methods of this interface.
 *
 *
 * You may want to start your implementation with this initial structure:
 *
 * <pre>`
 * public class CustomWorkerInterceptor extends WorkerInterceptorBase {
 * // remove if you don't need to have a custom WorkflowInboundCallsInterceptor or
 * // WorkflowOutboundCallsInterceptor
 * @Override
 * public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
 * return new CustomWorkflowInboundCallsInterceptor(next) {
 * // remove if you don't need to have a custom WorkflowOutboundCallsInterceptor
 * @Override
 * public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
 * next.init(new CustomWorkflowOutboundCallsInterceptor(outboundCalls));
 * }
 * };
 * }
 *
 * // remove if you don't need to have a custom ActivityInboundCallsInterceptor
 * @Override
 * public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
 * return new CustomActivityInboundCallsInterceptor(next);
 * }
 *
 * private static class CustomWorkflowInboundCallsInterceptor
 * extends WorkflowInboundCallsInterceptorBase {
 * public CustomWorkflowInboundCallsInterceptor(WorkflowInboundCallsInterceptor next) {
 * super(next);
 * }
 *
 * // override only the methods you need
 * }
 *
 * private static class CustomWorkflowOutboundCallsInterceptor
 * extends WorkflowOutboundCallsInterceptorBase {
 * public CustomWorkflowOutboundCallsInterceptor(WorkflowOutboundCallsInterceptor next) {
 * super(next);
 * }
 *
 * // override only the methods you need
 * }
 *
 * private static class CustomActivityInboundCallsInterceptor
 * extends ActivityInboundCallsInterceptorBase {
 * public CustomActivityInboundCallsInterceptor(ActivityInboundCallsInterceptor next) {
 * super(next);
 * }
 *
 * // override only the methods you need
 * }
 * }
`</pre> *
 */
@Experimental
interface KotlinWorkerInterceptor {
  /**
   * Called when workflow class is instantiated. May create a [ ] instance. The instance must forward all the calls to `next` [WorkflowInboundCallsInterceptor], but it may change the input parameters.
   *
   * @param next an existing interceptor instance to be proxied by the interceptor created inside
   * this method
   * @return an interceptor that passes all the calls to `next`
   */
  fun interceptWorkflow(next: WorkflowInboundCallsInterceptor): WorkflowInboundCallsInterceptor
  fun interceptActivity(next: ActivityInboundCallsInterceptor): ActivityInboundCallsInterceptor
}
