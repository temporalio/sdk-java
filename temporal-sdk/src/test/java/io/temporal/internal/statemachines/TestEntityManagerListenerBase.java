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

package io.temporal.internal.statemachines;

import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.internal.common.UpdateMessage;
import io.temporal.workflow.Functions;
import java.util.ArrayDeque;
import java.util.Queue;

abstract class TestEntityManagerListenerBase implements StatesMachinesCallback {

  private final Queue<Functions.Proc> callbacks = new ArrayDeque<>();

  @Override
  public final void start(HistoryEvent startWorkflowEvent) {
    buildWorkflow(AsyncWorkflowBuilder.newScheduler(callbacks, null));
  }

  protected abstract void buildWorkflow(AsyncWorkflowBuilder<Void> builder);

  @Override
  public final void signal(HistoryEvent signalEvent) {
    signal(signalEvent, AsyncWorkflowBuilder.newScheduler(callbacks, null));
  }

  protected void signal(HistoryEvent signalEvent, AsyncWorkflowBuilder<Void> builder) {}

  @Override
  public void update(UpdateMessage message) {
    update(message, AsyncWorkflowBuilder.newScheduler(callbacks, null));
  }

  protected void update(UpdateMessage message, AsyncWorkflowBuilder<Void> builder) {}

  @Override
  public void cancel(HistoryEvent cancelEvent) {}

  @Override
  public final void eventLoop() {
    while (true) {
      Functions.Proc callback = callbacks.poll();
      if (callback == null) {
        break;
      }
      callback.apply();
    }
  }
}
