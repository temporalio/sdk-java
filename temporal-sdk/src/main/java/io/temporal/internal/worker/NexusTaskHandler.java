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

package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.api.nexus.v1.Response;
import javax.annotation.Nullable;

public interface NexusTaskHandler {

  /**
   * Start the handler if the handler has any registered services. It is an error to start a handler
   * more than once.
   *
   * @return True if this handler can handle at least one nexus service.
   */
  boolean start();

  NexusTaskHandler.Result handle(NexusTask task, Scope metricsScope);

  class Result {
    @Nullable private final Response response;
    @Nullable private final HandlerError handlerError;

    public Result(Response response) {
      this.response = response;
      handlerError = null;
    }

    public Result(HandlerError handlerError) {
      this.handlerError = handlerError;
      response = null;
    }

    @Nullable
    public Response getResponse() {
      return response;
    }

    @Nullable
    public HandlerError getHandlerError() {
      return handlerError;
    }
  }
}
