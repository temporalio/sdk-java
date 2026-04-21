package io.temporal.client;

import static org.junit.Assert.*;

import com.google.common.reflect.TypeToken;
import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.common.Priority;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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

  @Test
  public void testBasicFields() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", "run-123"), CONVERTER);

    assertEquals("act-id", desc.getActivityId());
    assertEquals("run-123", desc.getActivityRunId());
    assertEquals("MyActivity", desc.getActivityType());
    assertEquals(ActivityExecutionStatus.ACTIVITY_EXECUTION_STATUS_RUNNING, desc.getStatus());
    assertEquals("my-queue", desc.getTaskQueue());
    assertEquals(2, desc.getAttempt());
  }

  @Test
  public void testNullRunIdWhenEmpty() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER);
    assertNull(desc.getActivityRunId());
  }

  @Test
  public void testNullableFieldsAbsentByDefault() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER);

    // These are all absent in the minimal response
    assertNull(desc.getCloseTime());
    assertNull(desc.getExecutionDuration());
    assertNull(desc.getCanceledReason());
    assertNull(desc.getCurrentRetryInterval());
    assertNull(desc.getExpirationTime());
    assertNull(desc.getHeartbeatTimeout());
    assertNull(desc.getLastAttemptCompleteTime());
    assertNull(desc.getLastHeartbeatTime());
    assertNull(desc.getLastStartedTime());
    assertNull(desc.getLastWorkerIdentity());
    assertNull(desc.getNextAttemptScheduleTime());
    assertNull(desc.getRetryOptions());
    assertNull(desc.getStaticSummary());
    assertNull(desc.getStaticDetails());
    assertFalse(desc.hasHeartbeatDetails());
    assertNull(desc.getWorkerDeploymentVersion());
    assertNull(desc.getPriority());
  }

  @Test
  public void testScheduledTime() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER);
    assertNotNull(desc.getScheduledTime());
    assertEquals(Instant.ofEpochMilli(1000), desc.getScheduledTime());
  }

  @Test
  public void testIsInstanceOfActivityExecution() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("id", "run"), CONVERTER);
    assertTrue(desc instanceof ActivityExecutionMetadata);
  }

  @Test
  public void testHasHeartbeatDetailsAbsent() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("id", "run"), CONVERTER);
    assertFalse(desc.hasHeartbeatDetails());
    assertFalse(desc.getHeartbeatDetails(String.class).isPresent());
  }

  @Test
  public void testGetHeartbeatDetailsPresent() {
    Payloads encoded = CONVERTER.toPayloads("hello-heartbeat").get();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setHeartbeatDetails(encoded).build();
    ActivityExecutionDescription desc = new ActivityExecutionDescription(info, CONVERTER);

    assertTrue(desc.hasHeartbeatDetails());
    Optional<String> result = desc.getHeartbeatDetails(String.class);
    assertTrue(result.isPresent());
    assertEquals("hello-heartbeat", result.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGetHeartbeatDetailsWithExplicitGenericType() {
    List<String> original = Arrays.asList("one", "two", "three");
    Payloads encoded = CONVERTER.toPayloads(original).get();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setHeartbeatDetails(encoded).build();
    ActivityExecutionDescription desc = new ActivityExecutionDescription(info, CONVERTER);

    Type genericType = new TypeToken<List<String>>() {}.getType();
    Class<List<String>> listClass = (Class<List<String>>) (Class<?>) List.class;
    Optional<List<String>> result = desc.getHeartbeatDetails(listClass, genericType);
    assertTrue(result.isPresent());
    assertEquals(Arrays.asList("one", "two", "three"), result.get());
  }

  @Test
  public void testGetWorkerDeploymentVersionAbsent() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("id", "run"), CONVERTER);
    assertNull(desc.getWorkerDeploymentVersion());
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
    ActivityExecutionDescription desc = new ActivityExecutionDescription(info, CONVERTER);

    WorkerDeploymentVersion version = desc.getWorkerDeploymentVersion();
    assertNotNull(version);
    assertEquals("my-deployment", version.getDeploymentName());
    assertEquals("build-42", version.getBuildId());
  }

  @Test
  public void testGetPriorityAbsent() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("id", "run"), CONVERTER);
    assertNull(desc.getPriority());
  }

  @Test
  public void testGetPriorityPresent() {
    io.temporal.api.common.v1.Priority protoPriority =
        io.temporal.api.common.v1.Priority.newBuilder().setPriorityKey(3).build();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setPriority(protoPriority).build();
    ActivityExecutionDescription desc = new ActivityExecutionDescription(info, CONVERTER);

    Priority priority = desc.getPriority();
    assertNotNull(priority);
    assertEquals(3, priority.getPriorityKey());
  }
}
