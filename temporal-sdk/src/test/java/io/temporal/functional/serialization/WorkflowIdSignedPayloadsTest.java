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

package io.temporal.functional.serialization;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import io.temporal.activity.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.client.schedules.*;
import io.temporal.common.converter.*;
import io.temporal.failure.CanceledFailure;
import io.temporal.payload.codec.PayloadCodec;
import io.temporal.payload.codec.PayloadCodecException;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.HasWorkflowSerializationContext;
import io.temporal.payload.context.SerializationContext;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflowWithCronScheduleImpl;
import io.temporal.workflow.shared.TestWorkflows;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * This test emulates a scenario when users may be using WorkflowId in their encoding to sign every
 * payload to be used for a specific workflow only to prevent a "payload replay" (had nothing to do
 * with Temporal Replay) attack. For this scenario it's important that every entity during its
 * serialization and deserialization gets the same SerializationContext, otherwise such signing will
 * explode on decoding.
 */
public class WorkflowIdSignedPayloadsTest {
  private static final String MEMO_KEY = "testKey";
  private static final String MEMO_VALUE = "testValue";
  private static final Map<String, Object> MEMO = ImmutableMap.of(MEMO_KEY, MEMO_VALUE);
  private final SimpleActivity heartbeatingActivity = new HeartbeatingIfNotLocalActivityImpl();
  private final ManualCompletionActivity manualCompletionActivity =
      new ManualCompletionActivityImpl();

  private static final DataConverter codecDataConverter =
      new CodecDataConverter(
          DefaultDataConverter.STANDARD_INSTANCE,
          Collections.singletonList(new PayloadEncoderWithWorkflowIdSignature()));

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              SimpleWorkflowWithAnActivity.class, TestWorkflowWithCronScheduleImpl.class)
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder().setDataConverter(codecDataConverter).build())
          .setActivityImplementations(heartbeatingActivity, manualCompletionActivity)
          .build();

  @Rule public TestName testName = new TestName();

  @Test
  public void testSimpleWorkflowWithAnActivity() {
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow1.class);
    assertEquals("result", workflowStub.execute("input"));
  }

  @Test
  public void testSimpleWorkflowWithMemo() throws InterruptedException {
    assumeTrue(
        "skipping as test server does not support list", SDKTestWorkflowRule.useExternalService);

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue());
    options = WorkflowOptions.newBuilder(options).setMemo(MEMO).build();
    TestWorkflows.TestWorkflow1 workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflow1.class, options);
    assertEquals("result", workflowStub.execute("input"));
    WorkflowExecution execution = WorkflowStub.fromTyped(workflowStub).getExecution();
    String workflowId = execution.getWorkflowId();
    String runId = execution.getRunId();

    // listWorkflowExecutions is Visibility API
    // Temporal Visibility has latency and is not transactional with the Server API call
    Thread.sleep(4_000);

    List<WorkflowExecutionMetadata> executions =
        testWorkflowRule
            .getWorkflowClient()
            .listExecutions("WorkflowId = '" + workflowId + "' AND " + " RunId = '" + runId + "'")
            .collect(Collectors.toList());
    assertEquals(1, executions.size());
    assertEquals(MEMO_VALUE, executions.get(0).getMemo(MEMO_KEY, String.class));
  }

  @Test
  public void testSimpleCronWorkflow() {
    assumeFalse("skipping as test will timeout", SDKTestWorkflowRule.useExternalService);

    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue());
    options =
        WorkflowOptions.newBuilder(options)
            .setWorkflowRunTimeout(Duration.ofHours(1))
            .setCronSchedule("0 */6 * * *")
            .build();
    TestWorkflows.TestWorkflowWithCronSchedule workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(TestWorkflows.TestWorkflowWithCronSchedule.class, options);

    testWorkflowRule.registerDelayedCallback(
        Duration.ofDays(1), WorkflowStub.fromTyped(workflow)::cancel);
    WorkflowClient.start(workflow::execute, testName.getMethodName());

    try {
      workflow.execute(testName.getMethodName());
      fail("unreachable");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause() instanceof CanceledFailure);
    }

    Map<Integer, String> lastCompletionResults =
        TestWorkflowWithCronScheduleImpl.lastCompletionResults.get(testName.getMethodName());
    assertEquals(4, lastCompletionResults.size());
    // Run 3 failed. So on run 4 we get the last completion result from run 2.
    assertEquals("run 2", lastCompletionResults.get(4));
    // The last failure ought to be the one from run 3
    assertTrue(TestWorkflowWithCronScheduleImpl.lastFail.isPresent());
    assertTrue(
        TestWorkflowWithCronScheduleImpl.lastFail.get().getMessage().contains("simulated error"));
  }

  @ActivityInterface
  public interface SimpleActivity {
    @ActivityMethod(name = "simple")
    String execute(String input);
  }

  @ActivityInterface
  public interface ManualCompletionActivity {
    @ActivityMethod(name = "manualCompletion")
    String execute(String input);
  }

  public static class HeartbeatingIfNotLocalActivityImpl implements SimpleActivity {
    @Override
    public String execute(String input) {
      assertEquals("input", input);

      if (!Activity.getExecutionContext().getInfo().isLocal()) {
        Activity.getExecutionContext().heartbeat("heartbeat");
        Optional<String> lastHeartbeat =
            Activity.getExecutionContext().getHeartbeatDetails(String.class);
        assertTrue(lastHeartbeat.isPresent());
        assertEquals("heartbeat", lastHeartbeat.get());
      }

      return "result";
    }
  }

  public static class ManualCompletionActivityImpl implements ManualCompletionActivity {
    @Override
    public String execute(String input) {
      assertEquals("input", input);
      ManualActivityCompletionClient manualActivityCompletionClient =
          Activity.getExecutionContext().useLocalManualCompletion();
      manualActivityCompletionClient.complete("result");
      return null;
    }
  }

  public static class SimpleCronWorkflow implements TestWorkflows.TestWorkflowReturnString {

    @Override
    public String execute() {
      return null;
    }
  }

  public static class SimpleWorkflowWithAnActivity implements TestWorkflows.TestWorkflow1 {

    private final SimpleActivity activity =
        Workflow.newActivityStub(
            SimpleActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(1)).build());

    private final SimpleActivity activityLocal =
        Workflow.newLocalActivityStub(
            SimpleActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(1))
                .build());

    private final ManualCompletionActivity manualCompletionActivity =
        Workflow.newActivityStub(
            ManualCompletionActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(1)).build());

    @Override
    public String execute(String input) {
      assertEquals("input", input);
      // Side Effect
      String sideEffectResult = Workflow.sideEffect(String.class, () -> "sideEffect");
      assertEquals("sideEffect", sideEffectResult);
      // Activity
      String result = activity.execute(input);
      assertEquals("result", result);
      // Local Activity
      result = activityLocal.execute(input);
      assertEquals("result", result);
      // Activity uses ManualCompletionClient to complete
      result = manualCompletionActivity.execute(input);
      assertEquals("result", result);
      // Child Workflow
      if (!Workflow.getInfo().getParentWorkflowId().isPresent()) {
        ChildWorkflowOptions childOptions = ChildWorkflowOptions.newBuilder().setMemo(MEMO).build();
        TestWorkflows.TestWorkflow1 child =
            Workflow.newChildWorkflowStub(TestWorkflows.TestWorkflow1.class, childOptions);
        result = child.execute(input);
        assertEquals("result", result);
      }
      // Memo
      String memoValue = (String) Workflow.getMemo(MEMO_KEY, String.class);
      if (memoValue != null) {
        assertEquals(MEMO_VALUE, memoValue);
      }
      // continueAsNew
      if (!Workflow.getInfo().getContinuedExecutionRunId().isPresent()) {
        ContinueAsNewOptions casOptions = ContinueAsNewOptions.newBuilder().setMemo(MEMO).build();
        Workflow.continueAsNew(casOptions, input);
      }
      return result;
    }
  }

  private static class PayloadEncoderWithWorkflowIdSignature implements PayloadCodec {
    private final ByteString METADATA_ENCODING = ByteString.copyFromUtf8("signed");

    private final SerializationContext serializationContext;

    public PayloadEncoderWithWorkflowIdSignature() {
      this(null);
    }

    public PayloadEncoderWithWorkflowIdSignature(
        @Nullable SerializationContext serializationContext) {
      this.serializationContext = serializationContext;
    }

    @Nonnull
    @Override
    public List<Payload> encode(@Nonnull List<Payload> payloads) {
      return payloads.stream().map(this::encodePayload).collect(Collectors.toList());
    }

    @Nonnull
    @Override
    public List<Payload> decode(@Nonnull List<Payload> payloads) {
      return payloads.stream().map(this::decodePayload).collect(Collectors.toList());
    }

    @Nonnull
    @Override
    public PayloadCodec withContext(@Nonnull SerializationContext context) {
      return new PayloadEncoderWithWorkflowIdSignature(context);
    }

    private Payload encodePayload(final Payload originalPayload) {
      String activityType = null;

      assertNotNull(
          "everything in this test should go through contextualized codecs", serializationContext);
      String workflowId = ((HasWorkflowSerializationContext) serializationContext).getWorkflowId();
      if (serializationContext instanceof ActivitySerializationContext) {
        activityType = ((ActivitySerializationContext) serializationContext).getActivityType();
      }
      String signature = activityType != null ? workflowId + activityType : workflowId;
      byte[] signatureBytes = signature.getBytes();

      byte[] decodedBytes = originalPayload.toByteArray();
      byte[] encodedBytes = new byte[decodedBytes.length + signatureBytes.length];
      System.arraycopy(decodedBytes, 0, encodedBytes, 0, decodedBytes.length);
      System.arraycopy(signatureBytes, 0, encodedBytes, decodedBytes.length, signatureBytes.length);

      return Payload.newBuilder()
          .putMetadata(EncodingKeys.METADATA_ENCODING_KEY, METADATA_ENCODING)
          .setData(ByteString.copyFrom(encodedBytes))
          .build();
    }

    private Payload decodePayload(final Payload originalPayload) {
      if (METADATA_ENCODING.equals(
          originalPayload.getMetadataOrDefault(EncodingKeys.METADATA_ENCODING_KEY, null))) {
        String activityType = null;

        assertNotNull(
            "everything in this test should go through contextualized codecs",
            serializationContext);
        String workflowId =
            ((HasWorkflowSerializationContext) serializationContext).getWorkflowId();

        if (serializationContext instanceof ActivitySerializationContext) {
          workflowId = ((ActivitySerializationContext) serializationContext).getWorkflowId();
          activityType = ((ActivitySerializationContext) serializationContext).getActivityType();
        }

        String expectedSignature = activityType != null ? workflowId + activityType : workflowId;
        byte[] expectedSignatureBytes = expectedSignature.getBytes();

        byte[] actualSignatureBytes = new byte[expectedSignatureBytes.length];
        byte[] encodedBytes = originalPayload.getData().toByteArray();
        System.arraycopy(
            encodedBytes,
            encodedBytes.length - actualSignatureBytes.length,
            actualSignatureBytes,
            0,
            actualSignatureBytes.length);
        assertArrayEquals(expectedSignatureBytes, actualSignatureBytes);

        byte[] bytesToDecode =
            Arrays.copyOfRange(encodedBytes, 0, encodedBytes.length - actualSignatureBytes.length);

        try {
          return Payload.parseFrom(bytesToDecode);
        } catch (IOException e) {
          throw new PayloadCodecException(e);
        }
      } else {
        fail("this code path shouldn't appear in this test, we want everything to be signed");
        // This payload is not encoded by this codec
        return originalPayload;
      }
    }
  }
}
