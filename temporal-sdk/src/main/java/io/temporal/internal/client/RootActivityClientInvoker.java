package io.temporal.internal.client;

import static io.temporal.internal.common.RetryOptionsUtils.toRetryPolicy;
import static io.temporal.internal.common.WorkflowExecutionUtils.makeUserMetaData;

import com.google.common.collect.Iterators;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.activity.v1.ActivityExecutionOutcome;
import io.temporal.api.common.v1.ActivityType;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.Link;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.errordetails.v1.ActivityExecutionAlreadyStartedFailure;
import io.temporal.api.sdk.v1.UserMetadata;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.*;
import io.temporal.client.*;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.interceptors.ActivityClientCallsInterceptor;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.HeaderUtils;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.common.ProtoConverters;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.InternalNexusOperationContext;
import io.temporal.internal.nexus.NexusOperationMetadata;
import io.temporal.serviceclient.StatusUtils;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.StreamSupport;

/**
 * Terminus of the activity interceptor chain. Implements all activity RPCs against the Temporal
 * service.
 */
public class RootActivityClientInvoker implements ActivityClientCallsInterceptor {

  private final GenericWorkflowClient genericClient;
  private final ActivityClientOptions clientOptions;

  public RootActivityClientInvoker(
      GenericWorkflowClient genericClient, ActivityClientOptions clientOptions) {
    this.genericClient = genericClient;
    this.clientOptions = clientOptions;
  }

  @Override
  public StartActivityOutput startActivity(StartActivityInput input) {
    StartActivityOptions options = input.getOptions();
    DataConverter dc = clientOptions.getDataConverter();
    InternalNexusOperationContext nexusContext =
        CurrentNexusOperationContext.isNexusContext() ? CurrentNexusOperationContext.get() : null;
    NexusOperationMetadata nexusOperationMetadata =
        nexusContext == null ? null : nexusContext.getNexusOperationMetadata();

    StartActivityExecutionRequest.Builder request =
        StartActivityExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setRequestId(
                nexusOperationMetadata == null
                    ? UUID.randomUUID().toString()
                    : nexusOperationMetadata.requestId)
            .setActivityId(options.getId())
            .setActivityType(ActivityType.newBuilder().setName(input.getActivityType()).build())
            .setTaskQueue(TaskQueue.newBuilder().setName(options.getTaskQueue()).build())
            .setIdReusePolicy(options.getIdReusePolicy())
            .setIdConflictPolicy(options.getIdConflictPolicy());

    Optional<Payloads> activityInput = dc.toPayloads(input.getArgs().toArray());
    activityInput.ifPresent(request::setInput);

    if (options.getScheduleToCloseTimeout() != null) {
      request.setScheduleToCloseTimeout(
          ProtobufTimeUtils.toProtoDuration(options.getScheduleToCloseTimeout()));
    }
    if (options.getScheduleToStartTimeout() != null) {
      request.setScheduleToStartTimeout(
          ProtobufTimeUtils.toProtoDuration(options.getScheduleToStartTimeout()));
    }
    if (options.getStartToCloseTimeout() != null) {
      request.setStartToCloseTimeout(
          ProtobufTimeUtils.toProtoDuration(options.getStartToCloseTimeout()));
    }
    if (options.getHeartbeatTimeout() != null) {
      request.setHeartbeatTimeout(ProtobufTimeUtils.toProtoDuration(options.getHeartbeatTimeout()));
    }
    if (options.getRetryOptions() != null) {
      request.setRetryPolicy(toRetryPolicy(options.getRetryOptions()));
    }
    if (options.getTypedSearchAttributes() != null
        && options.getTypedSearchAttributes().size() > 0) {
      request.setSearchAttributes(
          SearchAttributesUtil.encodeTyped(options.getTypedSearchAttributes()));
    }
    if (options.getStaticSummary() != null || options.getStaticDetails() != null) {
      UserMetadata userMetadata =
          makeUserMetaData(options.getStaticSummary(), options.getStaticDetails(), dc);
      if (userMetadata != null) {
        request.setUserMetadata(userMetadata);
      }
    }
    if (options.getPriority() != null) {
      request.setPriority(ProtoConverters.toProto(options.getPriority()));
    }
    if (options.getStartDelay() != null) {
      request.setStartDelay(ProtobufTimeUtils.toProtoDuration(options.getStartDelay()));
    }

    io.temporal.api.common.v1.Header grpcHeader = HeaderUtils.toHeaderGrpc(input.getHeader(), null);
    request.setHeader(grpcHeader);

    if (nexusOperationMetadata != null) {
      List<Link> protoLinks = nexusContext.getRequestLinks();
      request.addAllLinks(protoLinks);
      // Generate the operation token from the user-supplied activity ID and namespace so the
      // dual OPERATION_ID + OPERATION_TOKEN headers can be injected before the start RPC fires.
      try {
        nexusOperationMetadata.operationToken =
            io.temporal.internal.nexus.OperationTokenUtil.generateActivityExecutionOperationToken(
                options.getId(), clientOptions.getNamespace());
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new io.nexusrpc.handler.HandlerException(
            io.nexusrpc.handler.HandlerException.ErrorType.BAD_REQUEST,
            "failed to generate activity operation token",
            e);
      }
      Callback cb =
          InternalUtils.buildNexusCallback(
              nexusOperationMetadata.callbackUrl,
              nexusOperationMetadata.callbackHeaders,
              nexusOperationMetadata.operationToken,
              protoLinks);
      request.addCompletionCallbacks(cb);
    }

    StartActivityExecutionResponse response;
    try {
      response = genericClient.startActivity(request.build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.ALREADY_EXISTS) {
        ActivityExecutionAlreadyStartedFailure detail =
            StatusUtils.getFailure(e, ActivityExecutionAlreadyStartedFailure.class);
        if (detail != null) {
          String runId = detail.getRunId().isEmpty() ? null : detail.getRunId();
          throw new ActivityAlreadyStartedException(
              options.getId(), input.getActivityType(), runId, e);
        }
      }
      throw e;
    }

    String runId = response.getRunId().isEmpty() ? null : response.getRunId();
    return new StartActivityOutput(options.getId(), runId);
  }

  @Override
  public <R> GetActivityResultOutput<R> getActivityResult(GetActivityResultInput<R> input)
      throws TimeoutException {
    String namespace = clientOptions.getNamespace();
    DataConverter dc = clientOptions.getDataConverter();
    Deadline deadline = Deadline.after(input.getTimeout(), input.getTimeoutUnit());

    while (true) {
      PollActivityExecutionRequest.Builder pollRequest =
          PollActivityExecutionRequest.newBuilder()
              .setNamespace(namespace)
              .setActivityId(input.getActivityId());
      if (input.getRunId() != null) {
        pollRequest.setRunId(input.getRunId());
      }

      PollActivityExecutionResponse pollResponse;
      try {
        pollResponse = genericClient.pollActivity(pollRequest.build(), deadline);
      } catch (StatusRuntimeException e) {
        if (deadline.isExpired() && Status.Code.DEADLINE_EXCEEDED.equals(e.getStatus().getCode())) {
          throw new TimeoutException(
              "Activity did not complete within timeout: activityId='"
                  + input.getActivityId()
                  + "'");
        }
        throw e;
      }

      if (!pollResponse.hasOutcome()) {
        if (Thread.currentThread().isInterrupted()) {
          throw new ActivityFailedException(
              "Interrupted while waiting for activity result for activityId='"
                  + input.getActivityId()
                  + "'",
              input.getActivityId(),
              input.getRunId(),
              new InterruptedException());
        }
        continue;
      }

      ActivityExecutionOutcome outcome = pollResponse.getOutcome();
      switch (outcome.getValueCase()) {
        case RESULT:
          Type resultType =
              input.getResultType() != null ? input.getResultType() : input.getResultClass();
          @SuppressWarnings("unchecked")
          R result =
              (R)
                  dc.fromPayloads(
                      0,
                      outcome.hasResult() ? Optional.of(outcome.getResult()) : Optional.empty(),
                      input.getResultClass(),
                      resultType);
          return new GetActivityResultOutput<>(result);
        case FAILURE:
          throw new ActivityFailedException(
              "Activity failed: activityId='" + input.getActivityId() + "'",
              input.getActivityId(),
              input.getRunId(),
              dc.failureToException(outcome.getFailure()));
        default:
          throw new ActivityFailedException(
              "Activity completed with unexpected outcome '"
                  + outcome.getValueCase()
                  + "' for activityId='"
                  + input.getActivityId()
                  + "'",
              input.getActivityId(),
              input.getRunId(),
              null);
      }
    }
  }

  @Override
  public <R> CompletableFuture<GetActivityResultOutput<R>> getActivityResultAsync(
      GetActivityResultInput<R> input) {
    DataConverter dc = clientOptions.getDataConverter();
    Deadline deadline = Deadline.after(input.getTimeout(), input.getTimeoutUnit());
    return pollActivityUntilOutcome(input, deadline)
        .handle(
            (outcome, e) -> {
              if (e == null) {
                return decodeOutcome(outcome, input, dc);
              }
              throw handleAsyncException(e, deadline, input.getActivityId());
            });
  }

  private CompletableFuture<ActivityExecutionOutcome> pollActivityUntilOutcome(
      GetActivityResultInput<?> input, Deadline deadline) {
    PollActivityExecutionRequest.Builder pollRequest =
        PollActivityExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setActivityId(input.getActivityId());
    if (input.getRunId() != null) {
      pollRequest.setRunId(input.getRunId());
    }
    return genericClient
        .pollActivityAsync(pollRequest.build(), deadline)
        .thenComposeAsync(
            response -> {
              if (!response.hasOutcome()) {
                return pollActivityUntilOutcome(input, deadline);
              }
              return CompletableFuture.completedFuture(response.getOutcome());
            });
  }

  private static CompletionException handleAsyncException(
      Throwable e, Deadline deadline, String activityId) {
    Throwable cause = e instanceof CompletionException ? e.getCause() : e;
    if (deadline.isExpired()
        && cause instanceof StatusRuntimeException
        && Status.Code.DEADLINE_EXCEEDED.equals(
            ((StatusRuntimeException) cause).getStatus().getCode())) {
      return new CompletionException(
          new TimeoutException(
              "Activity did not complete within timeout: activityId='" + activityId + "'"));
    }
    return e instanceof CompletionException ? (CompletionException) e : new CompletionException(e);
  }

  private <R> GetActivityResultOutput<R> decodeOutcome(
      ActivityExecutionOutcome outcome, GetActivityResultInput<R> input, DataConverter dc) {
    switch (outcome.getValueCase()) {
      case RESULT:
        Type resultType =
            input.getResultType() != null ? input.getResultType() : input.getResultClass();
        @SuppressWarnings("unchecked")
        R result =
            (R)
                dc.fromPayloads(
                    0,
                    outcome.hasResult() ? Optional.of(outcome.getResult()) : Optional.empty(),
                    input.getResultClass(),
                    resultType);
        return new GetActivityResultOutput<>(result);
      case FAILURE:
        throw new java.util.concurrent.CompletionException(
            new ActivityFailedException(
                "Activity failed: activityId='" + input.getActivityId() + "'",
                input.getActivityId(),
                input.getRunId(),
                dc.failureToException(outcome.getFailure())));
      default:
        throw new java.util.concurrent.CompletionException(
            new ActivityFailedException(
                "Activity completed with unexpected outcome '"
                    + outcome.getValueCase()
                    + "' for activityId='"
                    + input.getActivityId()
                    + "'",
                input.getActivityId(),
                input.getRunId(),
                null));
    }
  }

  @Override
  public DescribeActivityOutput describeActivity(DescribeActivityInput input) {
    DescribeActivityExecutionRequest.Builder req =
        DescribeActivityExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setActivityId(input.getId());
    if (input.getRunId() != null) {
      req.setRunId(input.getRunId());
    }
    DescribeActivityExecutionResponse response = genericClient.describeActivity(req.build());
    return new DescribeActivityOutput(
        new ActivityExecutionDescription(
            response.getInfo(), clientOptions.getDataConverter(), clientOptions.getNamespace()));
  }

  @Override
  public CancelActivityOutput cancelActivity(CancelActivityInput input) {
    RequestCancelActivityExecutionRequest.Builder req =
        RequestCancelActivityExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setRequestId(UUID.randomUUID().toString())
            .setActivityId(input.getId());
    if (input.getRunId() != null) {
      req.setRunId(input.getRunId());
    }
    if (input.getReason() != null) {
      req.setReason(input.getReason());
    }
    genericClient.cancelActivity(req.build());
    return new CancelActivityOutput();
  }

  @Override
  public TerminateActivityOutput terminateActivity(TerminateActivityInput input) {
    TerminateActivityExecutionRequest.Builder req =
        TerminateActivityExecutionRequest.newBuilder()
            .setNamespace(clientOptions.getNamespace())
            .setIdentity(clientOptions.getIdentity())
            .setRequestId(UUID.randomUUID().toString())
            .setActivityId(input.getId());
    if (input.getRunId() != null) {
      req.setRunId(input.getRunId());
    }
    if (input.getReason() != null) {
      req.setReason(input.getReason());
    }
    genericClient.terminateActivity(req.build());
    return new TerminateActivityOutput();
  }

  @Override
  public ListActivitiesOutput listActivities(ListActivitiesInput input) {
    ListActivityExecutionIterator iterator =
        new ListActivityExecutionIterator(
            input.getQuery(), clientOptions.getNamespace(), genericClient);
    iterator.init();
    Iterator<ActivityExecutionMetadata> wrappedIterator =
        Iterators.transform(iterator, ActivityExecutionMetadata::fromListInfo);

    final int CHARACTERISTICS = Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE;
    return new ListActivitiesOutput(
        StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(wrappedIterator, CHARACTERISTICS), false));
  }

  @Override
  public CountActivitiesOutput countActivities(CountActivitiesInput input) {
    CountActivityExecutionsRequest.Builder req =
        CountActivityExecutionsRequest.newBuilder().setNamespace(clientOptions.getNamespace());
    if (input.getQuery() != null) {
      req.setQuery(input.getQuery());
    }
    CountActivityExecutionsResponse resp = genericClient.countActivities(req.build());
    return new CountActivitiesOutput(new ActivityExecutionCount(resp));
  }
}
