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

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.Experimental;

/**
 * Intercepts inbound calls to the activity execution on the worker side.
 *
 * <p>Prefer extending {@link ActivityInboundCallsInterceptorBase} and overriding only the methods
 * you need instead of implementing this interface directly. {@link
 * ActivityInboundCallsInterceptorBase} provides correct default implementations to all the methods
 * of this interface.
 */
@Experimental
public interface ActivityInboundCallsInterceptor {
  final class ActivityInput {
    private final Header header;
    private final Object[] arguments;

    public ActivityInput(Header header, Object[] arguments) {
      this.header = header;
      this.arguments = arguments;
    }

    public Header getHeader() {
      return header;
    }

    public Object[] getArguments() {
      return arguments;
    }
  }

  final class ActivityOutput {
    private final Object result;

    public ActivityOutput(Object result) {
      this.result = result;
    }

    public Object getResult() {
      return result;
    }
  }

  void init(ActivityExecutionContext context);

  /**
   * Intercepts a call to the main activity entry method.
   *
   * @return result of the activity execution.
   */
  ActivityOutput execute(ActivityInput input);
}
