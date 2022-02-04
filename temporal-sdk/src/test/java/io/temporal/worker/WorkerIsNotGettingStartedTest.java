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

package io.temporal.worker;

import static java.util.stream.Collectors.groupingBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkerIsNotGettingStartedTest {
  private static final int HOST_LOCAL_THREAD_COUNT = 22;
  private static final int WORKFLOW_POLL_COUNT = 11;
  private static final int ACTIVITY_POLL_COUNT = 18;

  private static final String TASK_QUEUE = "test-workflow";
  private static final String ACTIVITY_POLLER_THREAD_NAME_PREFIX = "Activity Poller task";
  private static final String WORKFLOW_POLLER_THREAD_NAME_PREFIX = "Workflow Poller task";
  private static final String WORKFLOW_HOST_LOCAL_POLLER_THREAD_NAME_PREFIX =
      "Host Local Workflow ";

  private TestWorkflowEnvironment env;
  private Worker worker;

  @Before
  public void setUp() throws Exception {
    TestEnvironmentOptions options =
        TestEnvironmentOptions.newBuilder()
            .setWorkerFactoryOptions(
                WorkerFactoryOptions.newBuilder()
                    .setWorkflowHostLocalPollThreadCount(HOST_LOCAL_THREAD_COUNT)
                    .build())
            .build();
    env = TestWorkflowEnvironment.newInstance(options);
    worker =
        env.newWorker(
            TASK_QUEUE,
            WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskPollers(WORKFLOW_POLL_COUNT)
                .setMaxConcurrentActivityTaskPollers(ACTIVITY_POLL_COUNT)
                .setLocalActivityWorkerOnly(true)
                .build());
    // Need to register something for workers to start
    worker.registerActivitiesImplementations(new LocalActivityWorkerOnlyTest.TestActivityImpl());
    worker.registerWorkflowImplementationTypes(
        LocalActivityWorkerOnlyTest.LocalActivityWorkflowImpl.class,
        LocalActivityWorkerOnlyTest.ActivityWorkflowImpl.class);
    env.start();
  }

  @After
  public void tearDown() throws Exception {
    env.close();
  }

  @Test
  public void verifyThatWorkerIsNotGettingStarted() throws InterruptedException {
    Thread.sleep(1000);
    Map<String, Long> threads =
        Thread.getAllStackTraces().keySet().stream()
            .map((t) -> t.getName().substring(0, Math.min(20, t.getName().length())))
            .collect(groupingBy(Function.identity(), Collectors.counting()));
    assertEquals(
        HOST_LOCAL_THREAD_COUNT, (long) threads.get(WORKFLOW_HOST_LOCAL_POLLER_THREAD_NAME_PREFIX));
    assertEquals(WORKFLOW_POLL_COUNT, (long) threads.get(WORKFLOW_POLLER_THREAD_NAME_PREFIX));
    assertFalse(threads.containsKey(ACTIVITY_POLLER_THREAD_NAME_PREFIX));
    assertNull(worker.activityWorker);
  }
}
