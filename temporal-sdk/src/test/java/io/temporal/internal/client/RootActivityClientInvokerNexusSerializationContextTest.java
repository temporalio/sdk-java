package io.temporal.internal.client;

import static io.temporal.internal.nexus.ContextRecordingCodec.contextOf;
import static io.temporal.internal.nexus.ContextRecordingCodec.stamp;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.uber.m3.tally.NoopScope;
import io.grpc.Deadline;
import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.activity.v1.ActivityExecutionOutcome;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.failure.v1.ApplicationFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.nexus.v1.PropagatedSerializationContext;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.api.workflowservice.v1.PollActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.PollActivityExecutionResponse;
import io.temporal.api.workflowservice.v1.StartActivityExecutionRequest;
import io.temporal.api.workflowservice.v1.StartActivityExecutionResponse;
import io.temporal.client.ActivityExecutionDescription;
import io.temporal.client.ActivityFailedException;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.GetActivityResultInput;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor.StartActivityInput;
import io.temporal.common.interceptors.Header;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.nexus.ContextRecordingCodec;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.context.NexusSerializationContext;
import io.temporal.payload.context.SerializationContext;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for propagating the Nexus serialization context to standalone activities started by a
 * Nexus operation handler, and for decoding their results with the context the server returns.
 */
public class RootActivityClientInvokerNexusSerializationContextTest {

  private static final String NAMESPACE = "test-namespace";
  private static final String ACTIVITY_TYPE = "TestActivity";
  private static final NexusSerializationContext NEXUS_CONTEXT =
      new NexusSerializationContext("endpoint", "service", "operation");
  private static final PropagatedSerializationContext PROPAGATED =
      PropagatedSerializationContext.newBuilder()
          .setEndpoint("endpoint")
          .setService("service")
          .setOperation("operation")
          .build();
  private static final String NEXUS = ContextRecordingCodec.describe(NEXUS_CONTEXT);

  private ContextRecordingCodec codec;
  private GenericWorkflowClient genericClient;
  private RootActivityClientInvoker invoker;

  @Before
  public void setUp() {
    codec = new ContextRecordingCodec();
    genericClient = mock(GenericWorkflowClient.class);
    invoker =
        new RootActivityClientInvoker(
            genericClient, NAMESPACE, "test-identity", codec.dataConverter());
  }

  @After
  public void tearDown() {
    CurrentNexusOperationContext.unset();
  }

  @Test
  public void startFromNexusHandlerPropagatesContextButEncodesInputWithActivityContext() {
    InternalNexusOperationContext nexusContext =
        new InternalNexusOperationContext(
            NAMESPACE, "tq", "endpoint", new NoopScope(), mock(WorkflowClient.class));
    nexusContext.setSerializationContext(NEXUS_CONTEXT);
    CurrentNexusOperationContext.set(nexusContext);
    when(genericClient.startActivity(any()))
        .thenReturn(StartActivityExecutionResponse.getDefaultInstance());

    invoker.startActivity(newStartInput());

    // Only the result and failure use the propagated context; the input and user metadata stay
    // within the activity.
    StartActivityExecutionRequest sent = captureStartRequest();
    Assert.assertEquals(PROPAGATED, sent.getPropagatedNexusSerializationContext());
    Assert.assertEquals("activity:" + ACTIVITY_TYPE, contextOf(sent.getInput().getPayloads(0)));
    Assert.assertEquals(
        "activity:" + ACTIVITY_TYPE, contextOf(sent.getUserMetadata().getSummary()));
  }

  @Test
  public void startOutsideNexusHandlerDoesNotPropagateContext() {
    when(genericClient.startActivity(any()))
        .thenReturn(StartActivityExecutionResponse.getDefaultInstance());

    invoker.startActivity(newStartInput());

    StartActivityExecutionRequest sent = captureStartRequest();
    Assert.assertFalse(sent.hasPropagatedNexusSerializationContext());
    Assert.assertEquals("activity:" + ACTIVITY_TYPE, contextOf(sent.getInput().getPayloads(0)));
  }

  @Test
  public void getResultDecodesWithPropagatedContext() throws Exception {
    when(genericClient.pollActivity(any(PollActivityExecutionRequest.class), any(Deadline.class)))
        .thenReturn(resultResponse(PROPAGATED));

    String result = invoker.getActivityResult(newGetResultInput()).getResult();

    Assert.assertEquals("result", result);
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void getResultWithoutPropagatedContextUsesActivityContext() throws Exception {
    when(genericClient.pollActivity(any(PollActivityExecutionRequest.class), any(Deadline.class)))
        .thenReturn(resultResponse(null));

    invoker.getActivityResult(newGetResultInput());

    // The result path doesn't know the activity type, so the activity context carries none.
    Assert.assertEquals(Collections.singletonList("activity:null"), codec.decodeContexts());
  }

  @Test
  public void getResultDecodesFailureWithPropagatedContext() {
    when(genericClient.pollActivity(any(PollActivityExecutionRequest.class), any(Deadline.class)))
        .thenReturn(failureResponse(PROPAGATED));

    Assert.assertThrows(
        ActivityFailedException.class, () -> invoker.getActivityResult(newGetResultInput()));
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void getResultAsyncDecodesWithPropagatedContext() throws Exception {
    when(genericClient.pollActivityAsync(
            any(PollActivityExecutionRequest.class), any(Deadline.class)))
        .thenReturn(CompletableFuture.completedFuture(resultResponse(PROPAGATED)));

    String result = invoker.getActivityResultAsync(newGetResultInput()).get().getResult();

    Assert.assertEquals("result", result);
    Assert.assertEquals(Collections.singletonList(NEXUS), codec.decodeContexts());
  }

  @Test
  public void describeDecodesOnlyResultAndFailuresWithPropagatedContext() {
    ActivityExecutionDescription description =
        new ActivityExecutionDescription(
            describeResponse(PROPAGATED), codec.dataConverter(), NAMESPACE);

    Assert.assertEquals("result", description.getResult(String.class).get());
    Assert.assertNotNull(description.getLastFailure());
    Assert.assertEquals(Arrays.asList(NEXUS, NEXUS), codec.decodeContexts());

    codec.reset();
    Assert.assertEquals("input", description.getInput().get(0, String.class));
    Assert.assertEquals("heartbeat", description.getHeartbeatDetails().get(0, String.class));
    Assert.assertEquals("summary", description.getSummary());
    String activity = "activity:" + ACTIVITY_TYPE;
    Assert.assertEquals(Arrays.asList(activity, activity, activity), codec.decodeContexts());
  }

  @Test
  public void describeWithoutPropagatedContextUsesActivityContext() {
    ActivityExecutionDescription description =
        new ActivityExecutionDescription(describeResponse(null), codec.dataConverter(), NAMESPACE);

    description.getResult(String.class);
    Assert.assertEquals(
        Collections.singletonList("activity:" + ACTIVITY_TYPE), codec.decodeContexts());
  }

  private StartActivityExecutionRequest captureStartRequest() {
    ArgumentCaptor<StartActivityExecutionRequest> captor =
        ArgumentCaptor.forClass(StartActivityExecutionRequest.class);
    verify(genericClient).startActivity(captor.capture());
    return captor.getValue();
  }

  private static StartActivityInput newStartInput() {
    StartActivityOptions options =
        StartActivityOptions.newBuilder()
            .setId("activity-id")
            .setTaskQueue("tq")
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setSummary("summary")
            .build();
    return new StartActivityInput(
        ACTIVITY_TYPE, Collections.singletonList("input"), options, Header.empty());
  }

  private static GetActivityResultInput<String> newGetResultInput() {
    return new GetActivityResultInput<>(
        "activity-id", null, String.class, String.class, 10, TimeUnit.SECONDS);
  }

  private static PollActivityExecutionResponse resultResponse(
      PropagatedSerializationContext propagated) {
    PollActivityExecutionResponse.Builder response =
        PollActivityExecutionResponse.newBuilder()
            .setOutcome(
                ActivityExecutionOutcome.newBuilder().setResult(payloads("result", propagated)));
    if (propagated != null) {
      response.setPropagatedNexusSerializationContext(propagated);
    }
    return response.build();
  }

  private static PollActivityExecutionResponse failureResponse(
      PropagatedSerializationContext propagated) {
    Failure failure =
        Failure.newBuilder()
            .setMessage("failed")
            .setApplicationFailureInfo(
                ApplicationFailureInfo.newBuilder()
                    .setType("TestFailure")
                    .setDetails(payloads("detail", propagated)))
            .build();
    PollActivityExecutionResponse.Builder response =
        PollActivityExecutionResponse.newBuilder()
            .setOutcome(ActivityExecutionOutcome.newBuilder().setFailure(failure));
    if (propagated != null) {
      response.setPropagatedNexusSerializationContext(propagated);
    }
    return response.build();
  }

  private static DescribeActivityExecutionResponse describeResponse(
      PropagatedSerializationContext propagated) {
    ActivityExecutionInfo.Builder info =
        ActivityExecutionInfo.newBuilder()
            .setActivityId("activity-id")
            .setActivityType(ActivityType.newBuilder().setName(ACTIVITY_TYPE))
            .setTaskQueue("tq");
    // Only the result and failures are encoded with the propagated context; the input, heartbeat
    // details and user metadata keep the activity context.
    info.setHeartbeatDetails(payloads("heartbeat", null))
        .setUserMetadata(
            UserMetadata.newBuilder().setSummary(payloads("summary", null).getPayloads(0)))
        .setLastFailure(
            Failure.newBuilder()
                .setMessage("attempt failed")
                .setApplicationFailureInfo(
                    ApplicationFailureInfo.newBuilder()
                        .setType("TestFailure")
                        .setDetails(payloads("detail", propagated))));
    if (propagated != null) {
      info.setPropagatedNexusSerializationContext(propagated);
    }
    return DescribeActivityExecutionResponse.newBuilder()
        .setInfo(info)
        .setInput(payloads("input", null))
        .setOutcome(ActivityExecutionOutcome.newBuilder().setResult(payloads("result", propagated)))
        .build();
  }

  private static Payloads payloads(String value, PropagatedSerializationContext propagated) {
    SerializationContext encodedWith =
        propagated != null
            ? NEXUS_CONTEXT
            : new ActivitySerializationContext(NAMESPACE, null, null, ACTIVITY_TYPE, "tq", false);
    return Payloads.newBuilder()
        .addPayloads(
            stamp(
                DefaultDataConverter.STANDARD_INSTANCE
                    .toPayload(value)
                    .orElseThrow(() -> new IllegalStateException("expected payload")),
                encodedWith))
        .build();
  }
}
