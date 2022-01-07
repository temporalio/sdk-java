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

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.Experimental;

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
