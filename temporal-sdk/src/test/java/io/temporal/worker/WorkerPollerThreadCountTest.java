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

import static java.util.stream.Collectors.groupingBy;
import static org.junit.Assert.assertEquals;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkerPollerThreadCountTest {

  private static final String ACTIVITY_POLLER_THREAD_NAME_PREFIX = "Activity Poller task";
  private static final String WORKFLOW_POLLER_THREAD_NAME_PREFIX = "Workflow Poller task";
  private static final String NEXUS_POLLER_THREAD_NAME_PREFIX = "Nexus Poller task";

  private static final int WORKFLOW_POLL_COUNT = 11;
  private static final int ACTIVITY_POLL_COUNT = 18;
  private static final int NEXUS_POLL_COUNT = 13;

  private TestWorkflowEnvironment env;

  @Before
  public void setUp() throws Exception {
    TestEnvironmentOptions options = TestEnvironmentOptions.newBuilder().build();
    env = TestWorkflowEnvironment.newInstance(options);
    Worker worker =
        env.newWorker(
            "tl1",
            WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskPollers(WORKFLOW_POLL_COUNT)
                .setMaxConcurrentActivityTaskPollers(ACTIVITY_POLL_COUNT)
                .setMaxConcurrentNexusTaskPollers(NEXUS_POLL_COUNT)
                .build());
    // Need to register something for workers to start
    worker.registerActivitiesImplementations(new ActivityImpl());
    worker.registerNexusServiceImplementation(new TestNexusServiceImpl());
    worker.registerWorkflowImplementationTypes(WorkflowImpl.class);
    env.start();
  }

  @After
  public void tearDown() throws Exception {
    env.close();
  }

  @ActivityInterface
  public interface Activity {
    void foo();
  }

  public static class ActivityImpl implements Activity {
    @Override
    public void foo() {}
  }

  @WorkflowInterface
  public interface Workflow {
    @WorkflowMethod
    void bar();
  }

  public static class WorkflowImpl implements Workflow {

    @Override
    public void bar() {}
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      // Implemented inline
      return OperationHandler.sync((ctx, details, name) -> "Hello, " + name + "!");
    }
  }

  @Test
  public void testPollThreadCount() throws InterruptedException {
    Thread.sleep(1000);
    Map<String, Long> threads =
        Thread.getAllStackTraces().keySet().stream()
            .filter(
                (t) ->
                    t.getName().startsWith(WORKFLOW_POLLER_THREAD_NAME_PREFIX)
                        || t.getName().startsWith(ACTIVITY_POLLER_THREAD_NAME_PREFIX)
                        || t.getName().startsWith(NEXUS_POLLER_THREAD_NAME_PREFIX))
            .map(Thread::getName)
            .collect(
                groupingBy(
                    (t) -> {
                      if (t.startsWith(WORKFLOW_POLLER_THREAD_NAME_PREFIX)) {
                        return WORKFLOW_POLLER_THREAD_NAME_PREFIX;
                      } else if (t.startsWith(ACTIVITY_POLLER_THREAD_NAME_PREFIX)) {
                        return ACTIVITY_POLLER_THREAD_NAME_PREFIX;
                      } else {
                        return NEXUS_POLLER_THREAD_NAME_PREFIX;
                      }
                    },
                    Collectors.counting()));
    assertEquals(WORKFLOW_POLL_COUNT, (long) threads.get(WORKFLOW_POLLER_THREAD_NAME_PREFIX));
    assertEquals(ACTIVITY_POLL_COUNT, (long) threads.get(ACTIVITY_POLLER_THREAD_NAME_PREFIX));
    assertEquals(NEXUS_POLL_COUNT, (long) threads.get(NEXUS_POLLER_THREAD_NAME_PREFIX));
  }
}
