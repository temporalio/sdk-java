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

package io.temporal.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class GenericParametersWorkflowTest {

  private final GenericParametersActivityImpl activitiesImpl = new GenericParametersActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(GenericParametersWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .build();

  @Test
  public void testGenericParametersWorkflow() throws ExecutionException, InterruptedException {
    GenericParametersWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(GenericParametersWorkflow.class);
    List<UUID> uuidList = new ArrayList<UUID>();
    uuidList.add(UUID.randomUUID());
    uuidList.add(UUID.randomUUID());
    Set<UUID> uuidSet = new HashSet<UUID>();
    uuidSet.add(UUID.randomUUID());
    uuidSet.add(UUID.randomUUID());
    uuidSet.add(UUID.randomUUID());
    CompletableFuture<List<UUID>> resultF =
        WorkflowClient.execute(
            workflowStub::execute, testWorkflowRule.getTaskQueue(), uuidList, uuidSet);
    // Test signal and query serialization
    workflowStub.signal(uuidList);
    testWorkflowRule.sleep(Duration.ofSeconds(1));
    List<UUID> queryArg = new ArrayList<UUID>();
    queryArg.add(UUID.randomUUID());
    queryArg.add(UUID.randomUUID());
    List<UUID> queryResult = workflowStub.query(queryArg);
    List<UUID> expectedQueryResult = new ArrayList<UUID>();
    expectedQueryResult.addAll(queryArg);
    expectedQueryResult.addAll(uuidList);
    expectedQueryResult.sort(UUID::compareTo);
    queryResult.sort(UUID::compareTo);
    Assert.assertEquals(expectedQueryResult, queryResult);
    workflowStub.signal(new ArrayList<UUID>()); // empty list unblocks workflow await.
    // test workflow result serialization
    List<UUID> expectedResult = new ArrayList<UUID>();
    expectedResult.addAll(uuidList);
    expectedResult.addAll(uuidSet);
    List<UUID> result = resultF.get();
    result.sort(UUID::compareTo);
    expectedResult.sort(UUID::compareTo);
    Assert.assertEquals(expectedResult, result);
  }

  @ActivityInterface
  public interface GenericParametersActivity {

    List<UUID> execute(List<UUID> arg1, Set<UUID> arg2);
  }

  @WorkflowInterface
  public interface GenericParametersWorkflow {

    @WorkflowMethod
    List<UUID> execute(String taskQueue, List<UUID> arg1, Set<UUID> arg2);

    @SignalMethod
    void signal(List<UUID> arg);

    @QueryMethod
    List<UUID> query(List<UUID> arg);
  }

  public static class GenericParametersActivityImpl implements GenericParametersActivity {

    @Override
    public List<UUID> execute(List<UUID> arg1, Set<UUID> arg2) {
      List<UUID> result = new ArrayList<>();
      result.addAll(arg1);
      result.addAll(arg2);
      return result;
    }
  }

  public static class GenericParametersWorkflowImpl implements GenericParametersWorkflow {

    private List<UUID> signaled;
    private GenericParametersActivity activity;

    @Override
    public List<UUID> execute(String taskQueue, List<UUID> arg1, Set<UUID> arg2) {
      Workflow.await(() -> signaled != null && signaled.size() == 0);
      activity =
          Workflow.newActivityStub(
              GenericParametersActivity.class,
              SDKTestOptions.newActivityOptionsForTaskQueue(taskQueue));
      return activity.execute(arg1, arg2);
    }

    @Override
    public void signal(List<UUID> arg) {
      signaled = arg;
    }

    @Override
    public List<UUID> query(List<UUID> arg) {
      List<UUID> result = new ArrayList<>();
      result.addAll(arg);
      result.addAll(signaled);
      return result;
    }
  }
}
