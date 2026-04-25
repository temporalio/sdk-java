package io.temporal.client;

import static org.junit.Assert.*;

import com.google.common.reflect.TypeToken;
import io.temporal.api.activity.v1.ActivityExecutionInfo;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.failure.v1.Failure;
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
  public void testNullRunIdWhenEmpty() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER, "test-ns", null);
    assertNull(desc.getActivityRunId());
  }

  @Test
  public void testNullableFieldsAbsentByDefault() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER, "test-ns", null);

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
    assertNull(desc.getLastFailure());
  }

  @Test
  public void testScheduledTime() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("act-id", ""), CONVERTER, "test-ns", null);
    assertEquals(Instant.ofEpochMilli(1000), desc.getScheduledTime());
  }

  @Test
  public void testHasHeartbeatDetailsAbsent() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("id", "run"), CONVERTER, "test-ns", null);
    assertFalse(desc.hasHeartbeatDetails());
    assertFalse(desc.getHeartbeatDetails(String.class).isPresent());
  }

  @Test
  public void testGetHeartbeatDetailsPresent() {
    Payloads encoded = CONVERTER.toPayloads("hello-heartbeat").get();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setHeartbeatDetails(encoded).build();
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(info, CONVERTER, "test-ns", null);

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
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(info, CONVERTER, "test-ns", null);

    Type genericType = new TypeToken<List<String>>() {}.getType();
    Class<List<String>> listClass = (Class<List<String>>) (Class<?>) List.class;
    Optional<List<String>> result = desc.getHeartbeatDetails(listClass, genericType);
    assertTrue(result.isPresent());
    assertEquals(Arrays.asList("one", "two", "three"), result.get());
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
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(info, CONVERTER, "test-ns", null);

    WorkerDeploymentVersion version = desc.getWorkerDeploymentVersion();
    assertNotNull(version);
    assertEquals("my-deployment", version.getDeploymentName());
    assertEquals("build-42", version.getBuildId());
  }

  @Test
  public void testGetLastFailureAbsent() {
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(buildInfo("id", "run"), CONVERTER, "test-ns", null);
    assertNull(desc.getLastFailure());
  }

  @Test
  public void testGetLastFailurePresent() {
    Failure failure = Failure.newBuilder().setMessage("boom").build();
    ActivityExecutionInfo info = buildInfo("id", "run").toBuilder().setLastFailure(failure).build();
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(info, CONVERTER, "test-ns", null);

    Failure result = desc.getLastFailure();
    assertNotNull(result);
    assertEquals("boom", result.getMessage());
  }

  @Test
  public void testGetRawInfo() {
    ActivityExecutionInfo info = buildInfo("id", "run");
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(info, CONVERTER, "test-ns", null);
    assertSame(info, desc.getRawInfo());
  }

  @Test
  public void testGetPriorityPresent() {
    io.temporal.api.common.v1.Priority protoPriority =
        io.temporal.api.common.v1.Priority.newBuilder().setPriorityKey(3).build();
    ActivityExecutionInfo info =
        buildInfo("id", "run").toBuilder().setPriority(protoPriority).build();
    ActivityExecutionDescription desc =
        new ActivityExecutionDescription(info, CONVERTER, "test-ns", null);

    Priority priority = desc.getPriority();
    assertNotNull(priority);
    assertEquals(3, priority.getPriorityKey());
  }
}
