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

package io.temporal.client.functional;

import static io.temporal.testing.internal.SDKTestWorkflowRule.NAMESPACE;
import static junit.framework.TestCase.assertEquals;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.StatsReporter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.serviceclient.MetricsTag;
import io.temporal.serviceclient.MetricsType;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class MetricsTest {

  private static final long REPORTING_FLUSH_TIME = 50;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(QuicklyCompletingWorkflowImpl.class)
          .build();

  private SimpleMeterRegistry registry;
  private WorkflowServiceStubs clientStubs;
  private WorkflowClient workflowClient;

  private static final List<Tag> TAGS_NAMESPACE =
      MetricsTag.defaultTags(NAMESPACE).entrySet().stream()
          .map(
              nameValueEntry ->
                  new ImmutableTag(nameValueEntry.getKey(), nameValueEntry.getValue()))
          .collect(Collectors.toList());

  private static List<Tag> TAGS_NAMESPACE_QUEUE;

  @Before
  public void setUp() {
    this.registry = new SimpleMeterRegistry();
    StatsReporter reporter = new MicrometerClientStatsReporter(registry);
    Scope metricsScope =
        new RootScopeBuilder()
            .reporter(reporter)
            .reportEvery(com.uber.m3.util.Duration.ofMillis(REPORTING_FLUSH_TIME >> 1));

    testWorkflowRule.getWorkflowClient().getWorkflowServiceStubs();
    WorkflowServiceStubsOptions options =
        testWorkflowRule.getWorkflowClient().getWorkflowServiceStubs().getOptions();
    WorkflowServiceStubsOptions modifiedOptions =
        WorkflowServiceStubsOptions.newBuilder(options).setMetricsScope(metricsScope).build();

    this.clientStubs = WorkflowServiceStubs.newServiceStubs(modifiedOptions);
    this.workflowClient =
        WorkflowClient.newInstance(clientStubs, testWorkflowRule.getWorkflowClient().getOptions());

    TAGS_NAMESPACE_QUEUE =
        replaceTags(TAGS_NAMESPACE, MetricsTag.TASK_QUEUE, testWorkflowRule.getTaskQueue());
  }

  @After
  public void tearDown() {
    this.clientStubs.shutdownNow();
    this.registry.close();
  }

  @Test
  public void testSynchronousStartAndGetResult() throws InterruptedException {
    QuicklyCompletingWorkflow quicklyCompletingWorkflow =
        workflowClient.newWorkflowStub(
            QuicklyCompletingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    quicklyCompletingWorkflow.execute();
    Thread.sleep(REPORTING_FLUSH_TIME);

    List<Tag> startRequestTags =
        replaceTags(
            TAGS_NAMESPACE_QUEUE,
            MetricsTag.OPERATION_NAME,
            "StartWorkflowExecution",
            MetricsTag.WORKFLOW_TYPE,
            "QuicklyCompletingWorkflow");

    assertIntCounter(1, registry.counter(MetricsType.TEMPORAL_REQUEST, startRequestTags));

    List<Tag> longPollRequestTags =
        replaceTag(TAGS_NAMESPACE, MetricsTag.OPERATION_NAME, "GetWorkflowExecutionHistory");

    assertIntCounter(1, registry.counter(MetricsType.TEMPORAL_LONG_REQUEST, longPollRequestTags));
  }

  @Test
  public void testAsynchronousStartAndGetResult() throws InterruptedException, ExecutionException {
    QuicklyCompletingWorkflow quicklyCompletingWorkflow =
        workflowClient.newWorkflowStub(
            QuicklyCompletingWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());
    WorkflowStub workflowStub = WorkflowStub.fromTyped(quicklyCompletingWorkflow);
    workflowStub.start();
    workflowStub.getResultAsync(String.class).get();
    Thread.sleep(REPORTING_FLUSH_TIME);

    List<Tag> startRequestTags =
        replaceTags(
            TAGS_NAMESPACE_QUEUE,
            MetricsTag.OPERATION_NAME,
            "StartWorkflowExecution",
            MetricsTag.WORKFLOW_TYPE,
            "QuicklyCompletingWorkflow");

    assertIntCounter(1, registry.counter(MetricsType.TEMPORAL_REQUEST, startRequestTags));

    List<Tag> longPollRequestTags =
        replaceTag(TAGS_NAMESPACE, MetricsTag.OPERATION_NAME, "GetWorkflowExecutionHistory");

    assertIntCounter(1, registry.counter(MetricsType.TEMPORAL_LONG_REQUEST, longPollRequestTags));
  }

  private static List<Tag> replaceTags(List<Tag> tags, String... nameValuePairs) {
    for (int i = 0; i < nameValuePairs.length; i += 2) {
      tags = replaceTag(tags, nameValuePairs[i], nameValuePairs[i + 1]);
    }
    return tags;
  }

  private static List<Tag> replaceTag(List<Tag> tags, String name, String value) {
    List<Tag> result =
        tags.stream().filter(tag -> !name.equals(tag.getKey())).collect(Collectors.toList());
    result.add(new ImmutableTag(name, value));
    return result;
  }

  private void assertIntCounter(int expectedValue, Counter counter) {
    assertEquals(expectedValue, Math.round(counter.count()));
  }

  @WorkflowInterface
  public interface QuicklyCompletingWorkflow {
    @WorkflowMethod
    String execute();
  }

  public static class QuicklyCompletingWorkflowImpl implements QuicklyCompletingWorkflow {

    @Override
    public String execute() {
      return "done";
    }
  }
}
