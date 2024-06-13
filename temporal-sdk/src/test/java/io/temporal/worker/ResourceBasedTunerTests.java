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

package io.temporal.worker;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.tuning.*;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class ResourceBasedTunerTests {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setWorkerTuner(
                      new ResourceBasedTuner(
                          ResourceBasedControllerOptions.newBuilder(0.5, 0.5).build()))
                  .build())
          .setActivityImplementations(new ActivitiesImpl())
          .setWorkflowTypes(ResourceTunerWorkflowImpl.class)
          .build();

  @Test
  public void canRunWithResourceBasedTuner() {
    ResourceTunerWorkflow workflow = testWorkflowRule.newWorkflowStub(ResourceTunerWorkflow.class);
    workflow.execute();
  }

  @WorkflowInterface
  public interface ResourceTunerWorkflow {

    @WorkflowMethod
    String execute();
  }

  public static class ResourceTunerWorkflowImpl implements ResourceTunerWorkflow {

    @Override
    public String execute() {
      SleepActivity activity =
          Workflow.newActivityStub(
              SleepActivity.class,
              ActivityOptions.newBuilder()
                  .setScheduleToStartTimeout(Duration.ofMinutes(1))
                  .setStartToCloseTimeout(Duration.ofMinutes(1))
                  .setHeartbeatTimeout(Duration.ofSeconds(20))
                  .build());

      List<Promise<Void>> promises = new ArrayList<>();
      // Run some activities concurrently
      for (int j = 0; j < 10; j++) {
        Promise<Void> promise = Async.procedure(activity::sleep);
        promises.add(promise);
      }

      for (Promise<Void> promise : promises) {
        promise.get();
      }

      return "I'm done";
    }
  }

  @ActivityInterface
  public interface SleepActivity {
    void sleep();
  }

  public static class ActivitiesImpl implements SleepActivity {
    @Override
    public void sleep() {}
  }
}
