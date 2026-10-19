package io.temporal.internal.nexus.system;

import io.temporal.api.common.v1.*;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.common.RetryOptions;
import io.temporal.common.VersioningOverride;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.*;
import io.temporal.payload.context.WorkflowSerializationContext;
import io.temporal.workflow.SignalWithStartWorkflowRequest;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** SDK-owned model conversion hooks used by the generated system Nexus bindings. */
public final class TemporalModelConverters {
  private TemporalModelConverters() {}

  public static String workflowNamespace() {
    return Workflow.getInfo().getNamespace();
  }

  public static WorkflowSerializationContext signalWithStartWorkflowSerializationContext(
      SignalWithStartWorkflowRequest request) {
    return new WorkflowSerializationContext(request.getNamespace(), request.getId());
  }

  public static RuntimeException failureFromProto(
      DataConverter converter, io.temporal.api.failure.v1.Failure value) {
    return converter.failureToException(value);
  }

  public static io.temporal.api.failure.v1.Failure failureToProto(
      DataConverter converter, RuntimeException value) {
    return converter.exceptionToFailure(value);
  }

  public static WorkflowType workflowTypeToProto(DataConverter converter, String value) {
    return WorkflowType.newBuilder().setName(value).build();
  }

  public static String workflowTypeFromProto(DataConverter converter, WorkflowType value) {
    return value.getName();
  }

  public static TaskQueue taskQueueToProto(DataConverter converter, String value) {
    return TaskQueue.newBuilder().setName(value).build();
  }

  public static String taskQueueFromProto(DataConverter converter, TaskQueue value) {
    return value.getName();
  }

  public static com.google.protobuf.Duration durationToProto(
      DataConverter converter, Duration value) {
    return ProtobufTimeUtils.toProtoDuration(value);
  }

  public static Duration durationFromProto(
      DataConverter converter, com.google.protobuf.Duration value) {
    return ProtobufTimeUtils.toJavaDuration(value);
  }

  public static RetryPolicy retryPolicyToProto(DataConverter converter, RetryOptions value) {
    return RetryOptionsUtils.toRetryPolicy(value).build();
  }

  public static RetryOptions retryPolicyFromProto(DataConverter converter, RetryPolicy value) {
    return RetryOptionsUtils.toRetryOptions(value);
  }

  public static Memo memoToProto(DataConverter converter, Map<String, Object> value) {
    Memo.Builder builder = Memo.newBuilder();
    value.forEach((key, item) -> builder.putFields(key, converter.toPayload(item).get()));
    return builder.build();
  }

  public static Map<String, Object> memoFromProto(DataConverter converter, Memo value) {
    Map<String, Object> result = new LinkedHashMap<>();
    value
        .getFieldsMap()
        .forEach(
            (key, payload) ->
                result.put(key, converter.fromPayload(payload, Object.class, Object.class)));
    return result;
  }

  public static Header headerToProto(DataConverter converter, Map<String, Payload> value) {
    Header.Builder builder = Header.newBuilder();
    value.forEach(
        (key, payload) ->
            builder.putFields(
                key,
                converter.toPayload(new io.temporal.common.converter.RawValue(payload)).get()));
    return builder.build();
  }

  public static Map<String, Payload> headerFromProto(DataConverter converter, Header value) {
    Map<String, Payload> result = new LinkedHashMap<>();
    value
        .getFieldsMap()
        .forEach(
            (key, payload) ->
                result.put(
                    key,
                    converter
                        .fromPayload(
                            payload,
                            io.temporal.common.converter.RawValue.class,
                            io.temporal.common.converter.RawValue.class)
                        .getPayload()));
    return result;
  }

  public static io.temporal.api.common.v1.SearchAttributes searchAttributesToProto(
      DataConverter converter, io.temporal.common.SearchAttributes value) {
    return SearchAttributesUtil.encodeTyped(value);
  }

  public static io.temporal.common.SearchAttributes searchAttributesFromProto(
      DataConverter converter, io.temporal.api.common.v1.SearchAttributes value) {
    return SearchAttributesUtil.decodeTyped(value);
  }

  public static Priority priorityToProto(
      DataConverter converter, io.temporal.common.Priority value) {
    return ProtoConverters.toProto(value);
  }

  public static io.temporal.common.Priority priorityFromProto(
      DataConverter converter, Priority value) {
    return ProtoConverters.fromProto(value);
  }

  public static io.temporal.api.workflow.v1.VersioningOverride versioningOverrideToProto(
      DataConverter converter, VersioningOverride value) {
    return ProtoConverters.toProto(value);
  }

  @SuppressWarnings("deprecation")
  public static VersioningOverride versioningOverrideFromProto(
      DataConverter converter, io.temporal.api.workflow.v1.VersioningOverride value) {
    if (value.hasOneTime())
      return new VersioningOverride.OneTimeVersioningOverride(
          deployment(value.getOneTime().getTargetDeploymentVersion()));
    if (value.hasPinned())
      return new VersioningOverride.PinnedVersioningOverride(
          deployment(value.getPinned().getVersion()));
    if (!value.getPinnedVersion().isEmpty())
      return new VersioningOverride.PinnedVersioningOverride(
          io.temporal.common.WorkerDeploymentVersion.fromCanonicalString(value.getPinnedVersion()));
    return new VersioningOverride.AutoUpgradeVersioningOverride();
  }

  private static io.temporal.common.WorkerDeploymentVersion deployment(
      io.temporal.api.deployment.v1.WorkerDeploymentVersion value) {
    return new io.temporal.common.WorkerDeploymentVersion(
        value.getDeploymentName(), value.getBuildId());
  }
}
