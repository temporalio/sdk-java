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

package io.temporal.workflow.versionTests;

import static io.temporal.internal.history.VersionMarkerUtils.TEMPORAL_CHANGE_VERSION;
import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class GetVersionMultipleCallsInSignalTest extends BaseVersionTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(getDefaultWorkflowImplementationOptions(), TestGetVersionInSignal.class)
          .build();

  public GetVersionMultipleCallsInSignalTest(
      boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test
  public void testMultipleLargeGetVersionInSignals() {
    TestSignaledWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestSignaledWorkflow.class);
    WorkflowClient.start(workflow::execute);

    char[] chars = new char[256];
    Arrays.fill(chars, 'a');
    String largeChangeIdPrefix = new String(chars);
    // Signal 10 times, each time with a different large change id
    for (int i = 0; i < 10; i++) {
      workflow.signal(largeChangeIdPrefix + "-" + i);
    }
    workflow.close();

    WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
    List<String> result = workflowStub.getResult(List.class);
    assertEquals(10, result.size());
    for (int i = 0; i < 10; i++) {
      assertEquals(largeChangeIdPrefix + "-" + i, result.get(i));
    }
    // Verify that the search attribute is set correctly
    List<String> versions =
        workflowStub.describe().getTypedSearchAttributes().get(TEMPORAL_CHANGE_VERSION);
    if (upsertVersioningSA) {
      assertEquals(7, versions.size());
      for (int i = 0; i < 7; i++) {
        assertEquals(largeChangeIdPrefix + "-" + i + "-2", versions.get(i));
      }
    } else {
      assertEquals(null, versions);
    }
  }

  @Test
  public void testMultipleLargeGetVersionInSignalsUpsertSAHistory() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testMultipleLargeGetVersionInSignalsUpsertSAHistory.json", TestGetVersionInSignal.class);
  }

  @Test
  public void testMultipleLargeGetVersionInSignalsHistory() throws Exception {
    WorkflowReplayer.replayWorkflowExecutionFromResource(
        "testMultipleLargeGetVersionInSignalsHistory.json", TestGetVersionInSignal.class);
  }

  @WorkflowInterface
  public interface TestSignaledWorkflow {

    @WorkflowMethod
    List<String> execute();

    @SignalMethod(name = "testSignal")
    void signal(String arg);

    @SignalMethod
    void close();
  }

  /** The following test covers the scenario where getVersion call is performed inside a signal */
  public static class TestGetVersionInSignal implements TestSignaledWorkflow {

    private final List<String> signalled = new ArrayList<>();
    private boolean closed = false;

    @Override
    public List<String> execute() {
      Workflow.await(() -> closed);
      return signalled;
    }

    @Override
    public void signal(String arg) {
      Workflow.getVersion(arg, 1, 2);
      signalled.add(arg);
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
