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

package io.temporal.workflow.failure;

import static io.temporal.internal.common.WorkflowExecutionUtils.getEventOfType;
import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.TemporalFailure;
import io.temporal.internal.testing.WorkflowTestingTest;
import io.temporal.internal.testing.WorkflowTestingTest.FailingWorkflowImpl;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Tests verifying that protobuf Failures are correctly codec-encoded and decoded.
 *
 * <p>More specifically:
 *
 * <ul>
 *   <li>If PayloadDataConverter was initialized with {@code encodeFailureAttributes = true}, then a
 *       failure's message and stack trace should be moved to the `encoded_attributes`, and the
 *       `encoded_attributes` should be processed through the registered codecs.
 *   <li>A workflow must still be able to read attributes of a failure that was thrown from an
 *       activity.
 *   <li>It is acceptable for Failure generated internally by the SDK be to not be codec-encoded if
 *       that Failure carries no sensible data and no stack trace.
 * </ul>
 */
public class FailureEncodingTest {
  private static final String TASK_QUEUE = "test-workflow";

  public @Rule Timeout timeout = Timeout.seconds(10);

  private TestWorkflowEnvironment testEnvironment;

  @Before
  public void setUp() {
    PrefixPayloadCodec prefixPayloadCodec = new PrefixPayloadCodec();
    CodecDataConverter dataConverter =
        new CodecDataConverter(
            DefaultDataConverter.newDefaultInstance(),
            Collections.singletonList(prefixPayloadCodec),
            true);

    WorkflowClientOptions workflowClientOptions =
        WorkflowClientOptions.newBuilder()
            .setDataConverter(dataConverter)
            .validateAndBuildWithDefaults();
    TestEnvironmentOptions testEnvOptions =
        TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(workflowClientOptions).build();
    testEnvironment = TestWorkflowEnvironment.newInstance(testEnvOptions);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @Test
  public void testExceptionThrownFromWorkflowIsCorrectlyEncoded() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(FailingWorkflowImpl.class);
    testEnvironment.start();

    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions workflowOptions = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, workflowOptions);

    try {
      workflow.execute("input1");
      fail("unreachable");
    } catch (WorkflowException e) {
      // Assert the exception can be correctly decoded
      assertEquals(
          "message='TestWorkflow1-input1', type='test', nonRetryable=false",
          e.getCause().getMessage());

      History history =
          client
              .fetchHistory(e.getExecution().getWorkflowId(), e.getExecution().getRunId())
              .getHistory();

      // Assert that exception is indeed encoded in the WorkflowExecutionFailedEvent
      HistoryEvent wfeFailedEvent =
          getEventOfType(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED);
      assertTrue(
          isEncoded(
              wfeFailedEvent
                  .getWorkflowExecutionFailedEventAttributes()
                  .getFailure()
                  .getEncodedAttributes()));
    }
  }

  @Test
  public void testExceptionThrownFromActivityIsCorrectlyEncoded() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(WorkflowTestingTest.ActivityWorkflow.class);
    worker.registerActivitiesImplementations(new FailingActivityImpl());
    testEnvironment.start();

    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions workflowOptions = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, workflowOptions);

    try {
      workflow.execute("input1");
      fail("unreachable");
    } catch (WorkflowException e) {
      // Assert the exception can be correctly decoded
      assertEquals(
          "Execute-input1", ((TemporalFailure) e.getCause().getCause()).getOriginalMessage());

      History history =
          client
              .fetchHistory(e.getExecution().getWorkflowId(), e.getExecution().getRunId())
              .getHistory();

      // Assert that exception is indeed encoded in the ActivityTaskFailedEvent
      HistoryEvent actTaskFailedEvent =
          getEventOfType(history, EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED);
      assertTrue(
          isEncoded(
              actTaskFailedEvent
                  .getActivityTaskFailedEventAttributes()
                  .getFailure()
                  .getEncodedAttributes()));

      // Assert that activity's exception is still encoded in the WorkflowExecutionFailedEvent
      HistoryEvent wfeFailedEvent =
          getEventOfType(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED);
      assertTrue(
          isEncoded(
              wfeFailedEvent
                  .getWorkflowExecutionFailedEventAttributes()
                  .getFailure()
                  .getCause()
                  .getEncodedAttributes()));
    }
  }

  @Test
  public void testExceptionThrownFromActivityIsReadableInWorkflow() {
    Worker worker = testEnvironment.newWorker(TASK_QUEUE);
    worker.registerWorkflowImplementationTypes(FailingActivityWorkflow.class);
    worker.registerActivitiesImplementations(new FailingActivityImpl());
    testEnvironment.start();

    WorkflowClient client = testEnvironment.getWorkflowClient();
    WorkflowOptions workflowOptions = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    TestWorkflow1 workflow = client.newWorkflowStub(TestWorkflow1.class, workflowOptions);

    String message = workflow.execute("input1");
    assertEquals("Execute-input1", message);
  }

  static boolean isEncoded(Payload payload) {
    return payload.getData().startsWith(PrefixPayloadCodec.PREFIX);
  }

  private static final class PrefixPayloadCodec implements PayloadCodec {
    public static final ByteString PREFIX = ByteString.copyFromUtf8("ENCODED: ");

    @Override
    @Nonnull
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      return payloads.stream().map(this::encode).collect(Collectors.toList());
    }

    private Payload encode(Payload decodedPayload) {
      ByteString encodedData = PREFIX.concat(decodedPayload.getData());
      return decodedPayload.toBuilder().setData(encodedData).build();
    }

    @Override
    @Nonnull
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      return payloads.stream().map(this::decode).collect(Collectors.toList());
    }

    private Payload decode(Payload encodedPayload) {
      ByteString encodedData = encodedPayload.getData();
      if (!encodedData.startsWith(PREFIX))
        throw new PayloadCodecException("Payload is not correctly encoded");
      ByteString decodedData = encodedData.substring(PREFIX.size());
      return encodedPayload.toBuilder().setData(decodedData).build();
    }
  }

  private static class FailingActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String input) {
      throw new IllegalThreadStateException(
          Activity.getExecutionContext().getInfo().getActivityType() + "-" + input);
    }
  }

  public static class FailingActivityWorkflow implements TestWorkflow1 {
    private final TestActivities.TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(2)).build());

    @Override
    public String execute(String input) {
      try {
        activity.execute(input);
        return "DID NOT FAILED";
      } catch (ActivityFailure e) {
        return ((TemporalFailure) e.getCause()).getOriginalMessage();
      }
    }
  }
}
