package io.temporal.client;

import static org.junit.Assert.*;

import com.google.common.reflect.TypeToken;
import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.activity.v1.ActivityExecutionOutcome;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeActivityExecutionResponse;
import io.temporal.common.Priority;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.failure.ApplicationFailure;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class ActivityExecutionDescriptionTest {

  private static final DataConverter CONVERTER = DefaultDataConverter.STANDARD_INSTANCE;

  private ActivityExecutionInfo buildInfo(String activityId, String runId) {
    return ActivityExecutionInfo.newBuilder()
        .setActivityId(activityId)
        .setRunId(runId)
        .setActivityType(ActivityType.newBuilder().setName("MyActivity").build())
        .setStatus(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING)
        .setTaskQueue("my-queue")
        .setAttempt(2)
        .setScheduleTime(ProtobufTimeUtils.toProtoTimestamp(Instant.ofEpochMilli(1000)))
        .build();
  }

  private ActivityExecutionDescription describe(ActivityExecutionInfo info) {
    return describe(DescribeActivityExecutionResponse.newBuilder().setInfo(info).build());
  }

  private ActivityExecutionDescription describe(DescribeActivityExecutionResponse response) {
    return new ActivityExecutionDescription(response, CONVERTER, "test-ns");
  }

  @Test
  public void testNullRunIdWhenEmpty() {
    ActivityExecutionDescription desc = describe(buildInfo("act-id", ""));
    assertNull(desc.getActivityRunId());
  }

  @Test
  public void testScheduledTime() {
    ActivityExecutionDescription desc = describe(buildInfo("act-id", ""));
    assertEquals(Instant.ofEpochMilli(1000), desc.getScheduledTime());
  }

  @Test
  public void testHasHeartbeatDetailsAbsent() {
    ActivityExecutionDescription desc = describe(buildInfo("id", "run"));
    assertFalse(desc.hasHeartbeatDetails());
    assertEquals(0, desc.getHeartbeatDetails().getSize());
  }

  @Test
  public void testGetHeartbeatDetailsPresent() {
    Payloads encoded = CONVERTER.toPayloads("hello-heartbeat").get();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setHeartbeatDetails(encoded).build();
    ActivityExecutionDescription desc = describe(info);

    assertTrue(desc.hasHeartbeatDetails());
    assertEquals(1, desc.getHeartbeatDetails().getSize());
    assertEquals("hello-heartbeat", desc.getHeartbeatDetails().get(0, String.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetHeartbeatDetailsWithExplicitGenericType() {
    List<String> original = Arrays.asList("one", "two", "three");
    Payloads encoded = CONVERTER.toPayloads(original).get();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setHeartbeatDetails(encoded).build();
    ActivityExecutionDescription desc = describe(info);

    Type genericType = new TypeToken<List<String>>() {}.getType();
    Class<List<String>> listClass = (Class<List<String>>) (Class<?>) List.class;
    assertEquals(
        Arrays.asList("one", "two", "three"),
        desc.getHeartbeatDetails().get(0, listClass, genericType));
  }

  @Test
  public void testGetWorkerDeploymentVersionPresent() {
    io.temporal.api.deployment.v1.WorkerDeploymentVersion protoVersion =
        io.temporal.api.deployment.v1.WorkerDeploymentVersion.newBuilder()
            .setDeploymentName("my-deployment")
            .setBuildId("build-42")
            .build();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setLastDeploymentVersion(protoVersion).build();
    ActivityExecutionDescription desc = describe(info);

    WorkerDeploymentVersion version = desc.getWorkerDeploymentVersion();
    assertNotNull(version);
    assertEquals("my-deployment", version.getDeploymentName());
    assertEquals("build-42", version.getBuildId());
  }

  @Test
  public void testInputAbsentUnlessRequested() {
    ActivityExecutionDescription desc = describe(buildInfo("id", "run"));
    assertFalse(desc.hasInput());
    assertEquals(0, desc.getInput().getSize());
  }

  @Test
  public void testGetInputPresent() {
    DescribeActivityExecutionResponse response =
        DescribeActivityExecutionResponse.newBuilder()
            .setInfo(buildInfo("id", "run"))
            .setInput(CONVERTER.toPayloads("hello-input").get())
            .build();
    ActivityExecutionDescription desc = describe(response);

    assertTrue(desc.hasInput());
    assertEquals(1, desc.getInput().getSize());
    assertEquals("hello-input", desc.getInput().get(0, String.class));
  }

  @Test
  public void testGetInputDecodesEveryArgument() {
    DescribeActivityExecutionResponse response =
        DescribeActivityExecutionResponse.newBuilder()
            .setInfo(buildInfo("id", "run"))
            .setInput(CONVERTER.toPayloads("first", 42).get())
            .build();
    ActivityExecutionDescription desc = describe(response);

    assertEquals(2, desc.getInput().getSize());
    assertEquals("first", desc.getInput().get(0, String.class));
    assertEquals(Integer.valueOf(42), desc.getInput().get(1, Integer.class));
  }

  @Test
  public void testInputEmptyWhenInputAbsent() {
    ActivityExecutionDescription desc = describe(buildInfo("id", "run"));
    assertEquals(0, desc.getInput().getSize());
  }

  @Test
  public void testOutcomeAbsentUnlessRequested() {
    ActivityExecutionDescription desc = describe(buildInfo("id", "run"));
    assertFalse(desc.hasResult());
    assertFalse(desc.getResult(String.class).isPresent());
    assertNull(desc.getOutcomeFailure());
  }

  @Test
  public void testGetResultPresentOnSuccessfulOutcome() {
    DescribeActivityExecutionResponse response =
        DescribeActivityExecutionResponse.newBuilder()
            .setInfo(buildInfo("id", "run"))
            .setOutcome(
                ActivityExecutionOutcome.newBuilder()
                    .setResult(CONVERTER.toPayloads("hello-result").get())
                    .build())
            .build();
    ActivityExecutionDescription desc = describe(response);

    assertTrue(desc.hasResult());
    assertEquals("hello-result", desc.getResult(String.class).orElse(null));
    // A successful outcome has no failure arm.
    assertNull(desc.getOutcomeFailure());
  }

  @Test
  public void testGetFailurePresentOnFailedOutcome() {
    DescribeActivityExecutionResponse response =
        DescribeActivityExecutionResponse.newBuilder()
            .setInfo(buildInfo("id", "run"))
            .setOutcome(
                ActivityExecutionOutcome.newBuilder()
                    .setFailure(
                        CONVERTER.exceptionToFailure(
                            ApplicationFailure.newFailure("boom", "test-type")))
                    .build())
            .build();
    ActivityExecutionDescription desc = describe(response);

    // The failure arm is populated, so there is no result to read.
    assertFalse(desc.hasResult());
    assertFalse(desc.getResult(String.class).isPresent());

    RuntimeException failure = desc.getOutcomeFailure();
    assertNotNull(failure);
    assertTrue(failure instanceof ApplicationFailure);
    assertEquals("boom", ((ApplicationFailure) failure).getOriginalMessage());
  }

  @Test
  public void testGetPriorityPresent() {
    io.temporal.api.common.v1.Priority protoPriority =
        io.temporal.api.common.v1.Priority.newBuilder().setPriorityKey(3).build();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setPriority(protoPriority).build();
    ActivityExecutionDescription desc = describe(info);

    Priority priority = desc.getPriority();
    assertNotNull(priority);
    assertEquals(3, priority.getPriorityKey());
  }
}
