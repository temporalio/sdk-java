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

package io.temporal.common.converter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.protobuf.ByteString;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
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

public class FailureConverterTest {
  private static final String TASK_QUEUE = "test-workflow";

  public @Rule Timeout timeout = Timeout.seconds(10);

  private CodecDataConverter dataConverter;

  private TestWorkflowEnvironment testEnvironment;

  @Before
  public void setUp() {
    PrefixPayloadCodec prefixPayloadCodec = new PrefixPayloadCodec();
    this.dataConverter =
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
  public void testMessageAndStackTraceAreCorrectlyEncoded() {
    try {
      ApplicationFailure causeException =
          ApplicationFailure.newFailure("CauseException", "CauseExceptionType");
      throw ApplicationFailure.newFailureWithCause("Message", "Type", causeException);
    } catch (ApplicationFailure originalException) {
      Failure failure = dataConverter.exceptionToFailure(originalException);

      // Assert the failure's message and stack trace were correctly moved to encoded attributes
      assertEquals("Encoded failure", failure.getMessage());
      assertEquals("", failure.getStackTrace());
      assertTrue(failure.hasEncodedAttributes());

      // Assert this was also done on the cause
      assertEquals("Encoded failure", failure.getCause().getMessage());
      assertEquals("", failure.getCause().getStackTrace());
      assertTrue(failure.getCause().hasEncodedAttributes());

      // Assert encoded_attributes were actually encoded
      assertTrue(isEncoded(failure.getEncodedAttributes()));
      assertTrue(isEncoded(failure.getCause().getEncodedAttributes()));
    }
  }

  @Test
  public void testMessageAndStackTraceAreCorrectlyDecoded() {
    try {
      ApplicationFailure causeException =
          ApplicationFailure.newFailure("CauseException", "CauseExceptionType");
      throw ApplicationFailure.newFailureWithCause("Message", "Type", causeException);
    } catch (ApplicationFailure originalException) {
      Failure failure = dataConverter.exceptionToFailure(originalException);
      Exception decodedException = dataConverter.failureToException(failure);

      assertEquals("Message", ((TemporalFailure) decodedException).getOriginalMessage());
      assertEquals(
          "CauseException", ((TemporalFailure) decodedException.getCause()).getOriginalMessage());

      assertEquals(
          originalException.getStackTrace()[0].toString(),
          decodedException.getStackTrace()[0].toString());
      assertEquals(
          originalException.getCause().getStackTrace()[0].toString(),
          decodedException.getCause().getStackTrace()[0].toString());
    }
  }

  @Test
  public void testDetailsAreEncoded() {
    Object[] details = new Object[] {"test", 123, new int[] {1, 2, 3}};

    ApplicationFailure originalException =
        ApplicationFailure.newFailure("Message", "Type", details);
    Failure failure = dataConverter.exceptionToFailure(originalException);
    Exception decodedException = dataConverter.failureToException(failure);

    // Assert details were actually encoded
    List<Payload> encodedDetailsPayloads =
        failure.getApplicationFailureInfo().getDetails().getPayloadsList();
    assertTrue(isEncoded(encodedDetailsPayloads.get(0)));
    assertTrue(isEncoded(encodedDetailsPayloads.get(1)));
    assertTrue(isEncoded(encodedDetailsPayloads.get(2)));

    // Assert details can be decoded
    Values decodedDetailsPayloads = ((ApplicationFailure) decodedException).getDetails();
    assertEquals("test", decodedDetailsPayloads.get(0, String.class, String.class));
    assertEquals((Integer) 123, decodedDetailsPayloads.get(1, Integer.class, Integer.class));
    assertArrayEquals(new int[] {1, 2, 3}, decodedDetailsPayloads.get(2, int[].class, int[].class));
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
      fail("unreacheable");
    } catch (WorkflowException e) {
      // Assert the exception can be correctly decoded
      assertEquals(
          "message='TestWorkflow1-input1', type='test', nonRetryable=false",
          e.getCause().getMessage());

      List<HistoryEvent> history =
          client
              .fetchHistory(e.getExecution().getWorkflowId(), e.getExecution().getRunId())
              .getHistory()
              .getEventsList();

      // Assert that exception is indeed encoded in the WorkflowExecutionFailedEvent
      HistoryEvent wfeFailedEvent =
          history.stream()
              .filter((x) -> x.hasWorkflowExecutionFailedEventAttributes())
              .findFirst()
              .get();
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
      fail("unreacheable");
    } catch (WorkflowException e) {
      // Assert the exception can be correctly decoded
      assertEquals(
          "Execute-input1", ((TemporalFailure) e.getCause().getCause()).getOriginalMessage());

      List<HistoryEvent> history =
          client
              .fetchHistory(e.getExecution().getWorkflowId(), e.getExecution().getRunId())
              .getHistory()
              .getEventsList();

      // Assert that exception is indeed encoded in the ActivityTaskFailedEvent
      HistoryEvent actTaskFailedEvent =
          history.stream()
              .filter((x) -> x.hasActivityTaskFailedEventAttributes())
              .findFirst()
              .get();
      assertTrue(
          isEncoded(
              actTaskFailedEvent
                  .getActivityTaskFailedEventAttributes()
                  .getFailure()
                  .getEncodedAttributes()));

      // Assert that activity's exception is still encoded in the WorkflowExecutionFailedEvent
      HistoryEvent wfeFailedEvent =
          history.stream()
              .filter((x) -> x.hasWorkflowExecutionFailedEventAttributes())
              .findFirst()
              .get();
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
