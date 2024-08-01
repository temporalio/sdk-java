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

package io.temporal.testserver.functional.common;

import io.temporal.workflow.*;

public class TestWorkflows {
  @WorkflowInterface
  public interface PrimitiveWorkflow {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface WorkflowTakesBool {
    @WorkflowMethod
    void execute(boolean arg);
  }

  @WorkflowInterface
  public interface WorkflowReturnsString {
    @WorkflowMethod
    String execute();
  }

  @WorkflowInterface
  public interface PrimitiveChildWorkflow {
    @WorkflowMethod
    void execute();
  }

  @WorkflowInterface
  public interface WorkflowWithUpdate {
    @WorkflowMethod
    void execute();

    @UpdateMethod
    void update(UpdateType type);

    @UpdateValidatorMethod(updateName = "update")
    void updateValidator(UpdateType type);

    @SignalMethod
    void signal();
  }

  public enum UpdateType {
    REJECT,
    COMPLETE,
    DELAYED_COMPLETE,
    BLOCK,
    FINISH_WORKFLOW,
  }
}
