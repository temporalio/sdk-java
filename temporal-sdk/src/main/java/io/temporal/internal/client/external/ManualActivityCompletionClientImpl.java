package io.temporal.internal.client.external;

import static io.temporal.serviceclient.MetricsTag.METRICS_TAGS_CALL_OPTIONS_KEY;

import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.uber.m3.tally.Scope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.activity.ManualActivityCompletionClient;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.*;
import io.temporal.common.CancellationToken;
import io.temporal.common.converter.DataConverter;
import io.temporal.failure.CanceledFailure;
import io.temporal.internal.client.ActivityClientHelper;
import io.temporal.internal.common.OptionsUtils;
import io.temporal.internal.payload.storage.ExternalStorageRunner;
import io.temporal.internal.retryer.GrpcRetryer;
import io.temporal.payload.context.ActivitySerializationContext;
import io.temporal.payload.storage.StorageDriverTargetInfo;
import io.temporal.serviceclient.RpcRetryOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ManualActivityCompletionClientImpl implements ManualActivityCompletionClient {

  private static final Logger log =
      LoggerFactory.getLogger(ManualActivityCompletionClientImpl.class);

  private final WorkflowServiceStubs service;
  private final WorkflowExecution execution;
  private final DataConverter dataConverterWithActivityExecutionContext;
  private final String namespace;
  private final String identity;
  private final String activityId;
  private final Scope metricsScope;
  private final byte[] taskToken;
  private final GrpcRetryer grpcRetryer;
  private final GrpcRetryer.GrpcRetryerOptions replyGrpcRetryerOptions;
  private final @Nullable StorageDriverTargetInfo storageTarget;
  private final @Nullable ExternalStorageRunner externalStorage;

  ManualActivityCompletionClientImpl(
      @Nonnull WorkflowServiceStubs service,
      @Nonnull String namespace,
      @Nonnull String identity,
      @Nonnull DataConverter dataConverter,
      @Nonnull Scope metricsScope,
      @Nullable byte[] taskToken,
      @Nullable WorkflowExecution execution,
      @Nullable String activityId,
      @Nullable ActivitySerializationContext context,
      @Nullable StorageDriverTargetInfo storageTarget,
      @Nullable ExternalStorageRunner externalStorage) {
    this.service = service;
    this.externalStorage = externalStorage;
    this.storageTarget = storageTarget;
    this.dataConverterWithActivityExecutionContext =
        context != null ? dataConverter.withContext(context) : dataConverter;
    this.namespace = namespace;
    this.identity = identity;
    this.metricsScope = metricsScope;
    this.grpcRetryer = new GrpcRetryer(service.getServerCapabilities());
    this.replyGrpcRetryerOptions =
        new GrpcRetryer.GrpcRetryerOptions(
            RpcRetryOptions.newBuilder()
                .buildWithDefaultsFrom(service.getOptions().getRpcRetryOptions()),
            null);

    Preconditions.checkArgument(
        taskToken != null && execution == null && activityId == null
            || taskToken == null && execution != null && activityId != null,
        "One of taskToken or (execution, activityId) must be specified");

    this.taskToken = taskToken;
    this.execution = execution;
    this.activityId = activityId;
  }

  private <T extends Message> T storeOutbound(T request) {
    if (externalStorage == null) {
      return request;
    }
    Message.Builder builder = request.toBuilder();
    externalStorage.store(builder, storageTarget, null, CancellationToken.none());
    @SuppressWarnings("unchecked")
    T stored = (T) builder.build();
    return stored;
  }

  @Override
  public void complete(@Nullable Object result) {
    Optional<Payloads> payloads = dataConverterWithActivityExecutionContext.toPayloads(result);
    if (taskToken != null) {
      RespondActivityTaskCompletedRequest.Builder builder =
          RespondActivityTaskCompletedRequest.newBuilder()
              .setNamespace(namespace)
              .setIdentity(identity)
              .setTaskToken(ByteString.copyFrom(taskToken));
      payloads.ifPresent(builder::setResult);
      try {
        RespondActivityTaskCompletedRequest request = storeOutbound(builder.build());
        grpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCompleted(request),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        processException(e);
      }
    } else {
      if (activityId == null) {
        throw new IllegalArgumentException("Either activity id or task token are required");
      }
      RespondActivityTaskCompletedByIdRequest.Builder builder =
          RespondActivityTaskCompletedByIdRequest.newBuilder()
              .setActivityId(activityId)
              .setNamespace(namespace)
              .setWorkflowId(execution.getWorkflowId())
              .setRunId(execution.getRunId());
      payloads.ifPresent(builder::setResult);
      try {
        RespondActivityTaskCompletedByIdRequest request = storeOutbound(builder.build());
        grpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCompletedById(request),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        processException(e);
      }
    }
  }

  @Override
  public void fail(@Nonnull Throwable exception) {
    Preconditions.checkNotNull(exception, "null exception");
    // When converting failures reason is class name, details are serialized exception.
    if (taskToken != null) {
      RespondActivityTaskFailedRequest.Builder builder =
          RespondActivityTaskFailedRequest.newBuilder()
              .setFailure(dataConverterWithActivityExecutionContext.exceptionToFailure(exception))
              .setNamespace(namespace)
              .setTaskToken(ByteString.copyFrom(taskToken));
      try {
        RespondActivityTaskFailedRequest request = storeOutbound(builder.build());
        grpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskFailed(request),
            replyGrpcRetryerOptions);
      } catch (StatusRuntimeException e) {
        if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
          throw new ActivityNotExistsException(e);
        }
        throw new ActivityCompletionFailureException(e);
      } catch (Exception e) {
        throw new ActivityCompletionFailureException(e);
      }
    } else {
      if (activityId == null) {
        throw new IllegalArgumentException("Either activity id or task token are required");
      }
      RespondActivityTaskFailedByIdRequest.Builder builder =
          RespondActivityTaskFailedByIdRequest.newBuilder()
              .setFailure(dataConverterWithActivityExecutionContext.exceptionToFailure(exception))
              .setNamespace(namespace)
              .setWorkflowId(execution.getWorkflowId())
              .setRunId(execution.getRunId())
              .setActivityId(activityId);
      try {
        RespondActivityTaskFailedByIdRequest request = storeOutbound(builder.build());
        grpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskFailedById(request),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        processException(e);
      }
    }
  }

  @Override
  public void recordHeartbeat(@Nullable Object details) throws CanceledFailure {
    try {
      Optional<Payloads> payloads = dataConverterWithActivityExecutionContext.toPayloads(details);
      if (taskToken != null) {
        RecordActivityTaskHeartbeatRequest.Builder builder =
            RecordActivityTaskHeartbeatRequest.newBuilder()
                .setNamespace(namespace)
                .setIdentity(identity)
                .setTaskToken(ByteString.copyFrom(taskToken));
        payloads.ifPresent(builder::setDetails);
        RecordActivityTaskHeartbeatRequest request = storeOutbound(builder.build());
        RecordActivityTaskHeartbeatResponse status =
            ActivityClientHelper.sendHeartbeatRequest(service, request, metricsScope);
        if (status.getCancelRequested()) {
          throw new ActivityCanceledException();
        } else if (status.getActivityReset()) {
          throw new ActivityResetException();
        } else if (status.getActivityPaused()) {
          throw new ActivityPausedException();
        }
      } else {
        RecordActivityTaskHeartbeatByIdRequest.Builder builder =
            RecordActivityTaskHeartbeatByIdRequest.newBuilder()
                .setNamespace(namespace)
                .setIdentity(identity)
                .setWorkflowId(execution.getWorkflowId())
                .setRunId(execution.getRunId())
                .setActivityId(activityId);
        payloads.ifPresent(builder::setDetails);
        RecordActivityTaskHeartbeatByIdRequest request = storeOutbound(builder.build());
        RecordActivityTaskHeartbeatByIdResponse status =
            ActivityClientHelper.recordActivityTaskHeartbeatById(service, request, metricsScope);
        if (status.getCancelRequested()) {
          throw new ActivityCanceledException();
        } else if (status.getActivityReset()) {
          throw new ActivityResetException();
        } else if (status.getActivityPaused()) {
          throw new ActivityPausedException();
        }
      }
    } catch (Exception e) {
      processException(e);
    }
  }

  @Override
  public void reportCancellation(@Nullable Object details) {
    Optional<Payloads> convertedDetails =
        dataConverterWithActivityExecutionContext.toPayloads(details);
    if (taskToken != null) {
      RespondActivityTaskCanceledRequest.Builder builder =
          RespondActivityTaskCanceledRequest.newBuilder()
              .setNamespace(namespace)
              .setTaskToken(ByteString.copyFrom(taskToken));
      convertedDetails.ifPresent(builder::setDetails);
      try {
        RespondActivityTaskCanceledRequest request = storeOutbound(builder.build());
        grpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCanceled(request),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        // There is nothing that can be done at this point.
        // so let's just ignore.
        log.info("reportCancellation", e);
      }
    } else {
      if (activityId == null) {
        throw new IllegalArgumentException("Either activity id or task token are required");
      }
      RespondActivityTaskCanceledByIdRequest.Builder builder =
          RespondActivityTaskCanceledByIdRequest.newBuilder()
              .setNamespace(namespace)
              .setWorkflowId(execution.getWorkflowId())
              .setRunId(OptionsUtils.safeGet(execution.getRunId()))
              .setActivityId(activityId);
      convertedDetails.ifPresent(builder::setDetails);
      try {
        RespondActivityTaskCanceledByIdRequest request = storeOutbound(builder.build());
        grpcRetryer.retry(
            () ->
                service
                    .blockingStub()
                    .withOption(METRICS_TAGS_CALL_OPTIONS_KEY, metricsScope)
                    .respondActivityTaskCanceledById(request),
            replyGrpcRetryerOptions);
      } catch (Exception e) {
        // There is nothing that can be done at this point.
        // so let's just ignore.
        log.warn("reportCancellation", e);
      }
    }
  }

  private void processException(Exception e) {
    if (e instanceof StatusRuntimeException) {
      StatusRuntimeException sre = (StatusRuntimeException) e;
      if (sre.getStatus().getCode() == Status.Code.NOT_FOUND) {
        throw new ActivityNotExistsException(activityId, sre);
      }
    }
    throw new ActivityCompletionFailureException(activityId, e);
  }
}
