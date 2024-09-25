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

package io.temporal.common.interceptors;

import com.uber.m3.tally.Scope;
import io.temporal.common.Experimental;

/**
 * Can be used to intercept calls from a Nexus operation into the Temporal APIs.
 *
 * <p>Prefer extending {@link NexusOutboundCallsInterceptorBase} and overriding only the methods you
 * need instead of implementing this interface directly. {@link NexusOutboundCallsInterceptorBase}
 * provides correct default implementations to all the methods of this interface.
 *
 * <p>An instance may be created in {@link
 * NexusInboundCallsInterceptor#init(NexusOutboundCallsInterceptor)} and set by passing it into
 * {@code init} method of the {@code next} {@link NexusInboundCallsInterceptor} The implementation
 * must forward all the calls to the outbound interceptor passed as a {@code outboundCalls}
 * parameter to the {@code init} call.
 */
@Experimental
public interface NexusOutboundCallsInterceptor {
  /** Intercepts call to get the metric scope in a Nexus operation. */
  Scope getMetricsScope();
}
