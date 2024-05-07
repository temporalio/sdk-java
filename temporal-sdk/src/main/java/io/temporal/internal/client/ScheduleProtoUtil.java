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

package io.temporal.internal.client;

import static io.temporal.internal.common.HeaderUtils.toHeaderGrpc;
import static io.temporal.internal.common.RetryOptionsUtils.toRetryPolicy;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.*;
import io.temporal.api.schedule.v1.*;
import io.temporal.api.schedule.v1.Schedule;
import io.temporal.api.schedule.v1.ScheduleAction;
import io.temporal.api.schedule.v1.ScheduleActionResult;
import io.temporal.api.schedule.v1.ScheduleInfo;
import io.temporal.api.schedule.v1.ScheduleSpec;
import io.temporal.api.schedule.v1.ScheduleState;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflow.v1.NewWorkflowExecutionInfo;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.*;
import io.temporal.common.RetryOptions;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DataConverter;
import io.temporal.common.converter.EncodedValues;
import io.temporal.internal.client.external.GenericWorkflowClient;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.RetryOptionsUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.payload.context.WorkflowSerializationContext;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ScheduleProtoUtil {

  private final GenericWorkflowClient genericClient;
  private final ScheduleClientOptions clientOptions;

  public ScheduleProtoUtil(
      GenericWorkflowClient genericClient, ScheduleClientOptions clientOptions) {
    this.genericClient = genericClient;
    this.clientOptions = clientOptions;
  }

  private io.temporal.common.interceptors.Header extractContextsAndConvertToBytes(
      List<ContextPropagator> scheduleOptionsContextPropagators) {
    List<ContextPropagator> scheduleClientContextPropagators =
        clientOptions.getContextPropagators();
    if ((scheduleClientContextPropagators.isEmpty() && scheduleOptionsContextPropagators == null)
        || (scheduleOptionsContextPropagators != null
            && scheduleOptionsContextPropagators.isEmpty())) {
      return null;
    }

    List<ContextPropagator> listToUse =
        MoreObjects.firstNonNull(
            scheduleOptionsContextPropagators, scheduleClientContextPropagators);
    Map<String, Payload> result = new HashMap<>();
    for (ContextPropagator propagator : listToUse) {
      result.putAll(propagator.serializeContext(propagator.getCurrentContext()));
    }
    return new io.temporal.common.interceptors.Header(result);
  }

  public ScheduleAction actionToProto(io.temporal.client.schedules.ScheduleAction action) {
    if (action instanceof ScheduleActionStartWorkflow) {
      ScheduleActionStartWorkflow startWorkflowAction = (ScheduleActionStartWorkflow) action;
      DataConverter dataConverterWithWorkflowContext =
          clientOptions
              .getDataConverter()
              .withContext(
                  new WorkflowSerializationContext(
                      clientOptions.getNamespace(),
                      startWorkflowAction.getOptions().getWorkflowId()));

      WorkflowOptions wfOptions = startWorkflowAction.getOptions();
      // Disallow some options
      if (wfOptions.getWorkflowIdReusePolicy() != null) {
        throw new IllegalArgumentException(
            "ID reuse policy cannot change from default for scheduled workflow");
      }
      if (wfOptions.getCronSchedule() != null) {
        throw new IllegalArgumentException("Cron schedule cannot be set on scheduled workflow");
      }
      if (wfOptions.getWorkflowIdConflictPolicy() != null) {
        throw new IllegalArgumentException(
                "ID conflict policy cannot change from default for scheduled workflow");
      }
      // Validate required options
      if (wfOptions.getWorkflowId() == null || wfOptions.getWorkflowId().isEmpty()) {
        throw new IllegalArgumentException("ID required on workflow action");
      }
      if (wfOptions.getTaskQueue() == null || wfOptions.getTaskQueue().isEmpty()) {
        throw new IllegalArgumentException("Task queue required on workflow action");
      }

      NewWorkflowExecutionInfo.Builder workflowRequest =
          NewWorkflowExecutionInfo.newBuilder()
              .setWorkflowId(wfOptions.getWorkflowId())
              .setWorkflowType(
                  WorkflowType.newBuilder().setName(startWorkflowAction.getWorkflowType()).build())
              .setWorkflowRunTimeout(
                  ProtobufTimeUtils.toProtoDuration(wfOptions.getWorkflowRunTimeout()))
              .setWorkflowExecutionTimeout(
                  ProtobufTimeUtils.toProtoDuration(wfOptions.getWorkflowExecutionTimeout()))
              .setWorkflowTaskTimeout(
                  ProtobufTimeUtils.toProtoDuration(wfOptions.getWorkflowTaskTimeout()))
              .setTaskQueue(TaskQueue.newBuilder().setName(wfOptions.getTaskQueue()).build());

      startWorkflowAction.getArguments().setDataConverter(dataConverterWithWorkflowContext);
      Optional<Payloads> inputArgs = startWorkflowAction.getArguments().toPayloads();
      if (inputArgs.isPresent()) {
        workflowRequest.setInput(inputArgs.get());
      }

      RetryOptions retryOptions = wfOptions.getRetryOptions();
      if (retryOptions != null) {
        workflowRequest.setRetryPolicy(toRetryPolicy(retryOptions));
      }

      if (startWorkflowAction.getOptions().getMemo() != null) {
        Map<String, Payload> memo = new HashMap<>();
        for (Map.Entry<String, Object> item :
            startWorkflowAction.getOptions().getMemo().entrySet()) {
          if (item.getValue() instanceof EncodedValues) {
            memo.put(
                item.getKey(), ((EncodedValues) item.getValue()).toPayloads().get().getPayloads(0));
          } else {
            memo.put(
                item.getKey(), dataConverterWithWorkflowContext.toPayload(item.getValue()).get());
          }
        }
        workflowRequest.setMemo(Memo.newBuilder().putAllFields(memo).build());
      }

      if (wfOptions.getTypedSearchAttributes() != null
          && wfOptions.getTypedSearchAttributes().size() > 0) {
        workflowRequest.setSearchAttributes(
            SearchAttributesUtil.encodeTyped(wfOptions.getTypedSearchAttributes()));
      }

      Header grpcHeader =
          toHeaderGrpc(
              startWorkflowAction.getHeader(),
              extractContextsAndConvertToBytes(wfOptions.getContextPropagators()));
      workflowRequest.setHeader(grpcHeader);

      return ScheduleAction.newBuilder().setStartWorkflow(workflowRequest.build()).build();
    }
    throw new IllegalArgumentException("Unsupported action " + action.getClass());
  }

  public SchedulePolicies policyToProto(SchedulePolicy policy) {
    SchedulePolicies.Builder builder = SchedulePolicies.newBuilder();
    if (policy.getCatchupWindow() != null) {
      builder.setCatchupWindow(ProtobufTimeUtils.toProtoDuration(policy.getCatchupWindow()));
    }
    builder.setPauseOnFailure(policy.isPauseOnFailure());
    builder.setOverlapPolicy(policy.getOverlap());
    return builder.build();
  }

  public List<Range> scheduleRangeToProto(List<ScheduleRange> scheduleRanges) {
    ArrayList<Range> ranges = new ArrayList<Range>(scheduleRanges.size());
    for (ScheduleRange scheduleRange : scheduleRanges) {
      ranges.add(
          Range.newBuilder()
              .setStart(scheduleRange.getStart())
              .setEnd(scheduleRange.getEnd())
              .setStep(scheduleRange.getStep())
              .build());
    }
    return ranges;
  }

  public ScheduleSpec specToProto(io.temporal.client.schedules.ScheduleSpec spec) {
    ScheduleSpec.Builder builder = ScheduleSpec.newBuilder();

    if (spec.getTimeZoneName() != null && !spec.getTimeZoneName().isEmpty()) {
      builder.setTimezoneName(spec.getTimeZoneName());
    }

    if (spec.getJitter() != null) {
      builder.setJitter(ProtobufTimeUtils.toProtoDuration(spec.getJitter()));
    }

    if (spec.getStartAt() != null) {
      builder.setStartTime(ProtobufTimeUtils.toProtoTimestamp(spec.getStartAt()));
    }

    if (spec.getEndAt() != null) {
      builder.setEndTime(ProtobufTimeUtils.toProtoTimestamp(spec.getEndAt()));
    }

    if (spec.getCalendars() != null && !spec.getCalendars().isEmpty()) {
      for (ScheduleCalendarSpec calendarSpec : spec.getCalendars()) {
        builder.addStructuredCalendar(
            StructuredCalendarSpec.newBuilder()
                .addAllSecond(this.scheduleRangeToProto(calendarSpec.getSeconds()))
                .addAllMinute(this.scheduleRangeToProto(calendarSpec.getMinutes()))
                .addAllHour(this.scheduleRangeToProto(calendarSpec.getHour()))
                .addAllDayOfMonth(this.scheduleRangeToProto(calendarSpec.getDayOfMonth()))
                .addAllMonth(this.scheduleRangeToProto(calendarSpec.getMonth()))
                .addAllYear(this.scheduleRangeToProto(calendarSpec.getYear()))
                .addAllDayOfWeek(this.scheduleRangeToProto(calendarSpec.getDayOfWeek()))
                .setComment(calendarSpec.getComment())
                .build());
      }
    }

    if (spec.getIntervals() != null && !spec.getIntervals().isEmpty()) {
      for (ScheduleIntervalSpec intervalSpec : spec.getIntervals()) {
        builder.addInterval(
            IntervalSpec.newBuilder()
                .setInterval(ProtobufTimeUtils.toProtoDuration(intervalSpec.getEvery()))
                .setPhase(ProtobufTimeUtils.toProtoDuration(intervalSpec.getOffset()))
                .build());
      }
    }

    if (spec.getCronExpressions() != null && !spec.getCronExpressions().isEmpty()) {
      builder.addAllCronString(spec.getCronExpressions());
    }

    if (spec.getSkip() != null && !spec.getSkip().isEmpty()) {
      for (ScheduleCalendarSpec calendarSpec : spec.getSkip()) {
        builder.addExcludeStructuredCalendar(
            StructuredCalendarSpec.newBuilder()
                .addAllSecond(this.scheduleRangeToProto(calendarSpec.getSeconds()))
                .addAllMinute(this.scheduleRangeToProto(calendarSpec.getMinutes()))
                .addAllHour(this.scheduleRangeToProto(calendarSpec.getHour()))
                .addAllDayOfMonth(this.scheduleRangeToProto(calendarSpec.getDayOfMonth()))
                .addAllMonth(this.scheduleRangeToProto(calendarSpec.getMonth()))
                .addAllYear(this.scheduleRangeToProto(calendarSpec.getYear()))
                .addAllDayOfWeek(this.scheduleRangeToProto(calendarSpec.getDayOfWeek()))
                .setComment(calendarSpec.getComment())
                .build());
      }
    }

    return builder.build();
  }

  public @Nonnull Schedule scheduleToProto(
      @Nonnull io.temporal.client.schedules.Schedule schedule) {
    Preconditions.checkNotNull(schedule);

    Schedule.Builder scheduleBuilder =
        Schedule.newBuilder()
            .setAction(this.actionToProto(schedule.getAction()))
            .setSpec(this.specToProto(schedule.getSpec()));

    if (schedule.getPolicy() != null) {
      scheduleBuilder.setPolicies(this.policyToProto(schedule.getPolicy()));
    }

    if (schedule.getState() != null) {
      scheduleBuilder.setState(this.stateToProto(schedule.getState()));
    }

    return scheduleBuilder.build();
  }

  public ScheduleState stateToProto(io.temporal.client.schedules.ScheduleState state) {
    ScheduleState.Builder stateBuilder =
        ScheduleState.newBuilder()
            .setLimitedActions(state.isLimitedAction())
            .setRemainingActions(state.getRemainingActions())
            .setPaused(state.isPaused());
    if (state.getNote() != null) {
      stateBuilder.setNotes(state.getNote());
    }
    return stateBuilder.build();
  }

  public ScheduleRange protoToScheduleRange(Range protoRange) {
    return new ScheduleRange(protoRange.getStart(), protoRange.getEnd(), protoRange.getStep());
  }

  public ScheduleIntervalSpec protoToScheduleInterval(IntervalSpec protoInterval) {
    return new ScheduleIntervalSpec(
        ProtobufTimeUtils.toJavaDuration(protoInterval.getInterval()),
        ProtobufTimeUtils.toJavaDuration(protoInterval.getPhase()));
  }

  public List<ScheduleRange> protoToScheduleRanges(List<Range> protoRanges) {
    return protoRanges.stream().map(t -> this.protoToScheduleRange(t)).collect(Collectors.toList());
  }

  public ScheduleCalendarSpec protoToScheduleCalendar(StructuredCalendarSpec protoSpec) {
    ScheduleCalendarSpec.Builder calendarBuilder =
        ScheduleCalendarSpec.newBuilder()
            .setComment(protoSpec.getComment())
            .setSeconds(protoToScheduleRanges(protoSpec.getSecondList()))
            .setMinutes(protoToScheduleRanges(protoSpec.getMinuteList()))
            .setHour(protoToScheduleRanges(protoSpec.getHourList()))
            .setDayOfMonth(protoToScheduleRanges(protoSpec.getDayOfMonthList()))
            .setMonth(protoToScheduleRanges(protoSpec.getMonthList()))
            .setYear(protoToScheduleRanges(protoSpec.getYearList()))
            .setDayOfWeek(protoToScheduleRanges(protoSpec.getDayOfWeekList()));

    return calendarBuilder.build();
  }

  @Nonnull
  public io.temporal.client.schedules.ScheduleSpec protoToScheduleSpec(
      @Nonnull ScheduleSpec scheduleSpec) {
    Objects.requireNonNull(scheduleSpec);
    io.temporal.client.schedules.ScheduleSpec.Builder specBuilder =
        io.temporal.client.schedules.ScheduleSpec.newBuilder()
            .setTimeZoneName(
                scheduleSpec.getTimezoneName() == null ? "" : scheduleSpec.getTimezoneName());

    if (scheduleSpec.hasJitter()) {
      specBuilder.setJitter(ProtobufTimeUtils.toJavaDuration(scheduleSpec.getJitter()));
    }

    if (scheduleSpec.hasStartTime()) {
      specBuilder.setStartAt(ProtobufTimeUtils.toJavaInstant(scheduleSpec.getStartTime()));
    }

    if (scheduleSpec.hasEndTime()) {
      specBuilder.setEndAt(ProtobufTimeUtils.toJavaInstant(scheduleSpec.getEndTime()));
    }

    specBuilder.setCalendars(
        scheduleSpec.getStructuredCalendarList().stream()
            .map(c -> this.protoToScheduleCalendar(c))
            .collect(Collectors.toList()));

    specBuilder.setCronExpressions(scheduleSpec.getCronStringList());

    specBuilder.setIntervals(
        scheduleSpec.getIntervalList().stream()
            .map(i -> this.protoToScheduleInterval(i))
            .collect(Collectors.toList()));

    specBuilder.setSkip(
        scheduleSpec.getExcludeStructuredCalendarList().stream()
            .map(c -> this.protoToScheduleCalendar(c))
            .collect(Collectors.toList()));

    return specBuilder.build();
  }

  public List<io.temporal.client.schedules.ScheduleActionResult> protoToActionResults(
      List<ScheduleActionResult> results) {
    return results.stream()
        .map(
            a ->
                new io.temporal.client.schedules.ScheduleActionResult(
                    ProtobufTimeUtils.toJavaInstant(a.getScheduleTime()),
                    ProtobufTimeUtils.toJavaInstant(a.getActualTime()),
                    new ScheduleActionExecutionStartWorkflow(
                        a.getStartWorkflowResult().getWorkflowId(),
                        a.getStartWorkflowResult().getRunId())))
        .collect(Collectors.toList());
  }

  public ScheduleListDescription protoToScheduleListDescription(ScheduleListEntry entry) {
    List<Instant> nextActionTimes =
        entry.getInfo().getFutureActionTimesList().stream()
            .map(ProtobufTimeUtils::toJavaInstant)
            .collect(Collectors.toList());

    io.temporal.client.schedules.ScheduleListInfo info =
        new io.temporal.client.schedules.ScheduleListInfo(
            this.protoToActionResults(entry.getInfo().getRecentActionsList()), nextActionTimes);

    ScheduleListActionStartWorkflow action =
        new ScheduleListActionStartWorkflow(entry.getInfo().getWorkflowType().getName());

    ScheduleListState state =
        new ScheduleListState(entry.getInfo().getNotes(), entry.getInfo().getPaused());

    io.temporal.client.schedules.ScheduleSpec spec = protoToScheduleSpec(entry.getInfo().getSpec());

    return new ScheduleListDescription(
        entry.getScheduleId(),
        new ScheduleListSchedule(action, spec, state),
        info,
        entry.getMemo().getFieldsMap(),
        this.clientOptions.getDataConverter(),
        Collections.unmodifiableMap(SearchAttributesUtil.decode(entry.getSearchAttributes())));
  }

  @Nonnull
  public io.temporal.client.schedules.ScheduleAction protoToAction(@Nonnull ScheduleAction action) {
    Objects.requireNonNull(action);
    if (action.hasStartWorkflow()) {
      NewWorkflowExecutionInfo startWfAction = action.getStartWorkflow();
      DataConverter dataConverterWithWorkflowContext =
          clientOptions
              .getDataConverter()
              .withContext(
                  new WorkflowSerializationContext(
                      clientOptions.getNamespace(), startWfAction.getWorkflowId()));

      ScheduleActionStartWorkflow.Builder builder = ScheduleActionStartWorkflow.newBuilder();
      builder.setWorkflowType(startWfAction.getWorkflowType().getName());

      builder.setRawArguments(
          new EncodedValues(
              Optional.of(startWfAction.getInput()), dataConverterWithWorkflowContext));

      WorkflowOptions.Builder wfOptionsBuilder = WorkflowOptions.newBuilder();
      // set required options
      wfOptionsBuilder.setWorkflowId(startWfAction.getWorkflowId());
      wfOptionsBuilder.setTaskQueue(startWfAction.getTaskQueue().getName());
      // set timeouts
      wfOptionsBuilder.setWorkflowExecutionTimeout(
          ProtobufTimeUtils.toJavaDuration(startWfAction.getWorkflowExecutionTimeout()));

      wfOptionsBuilder.setWorkflowRunTimeout(
          ProtobufTimeUtils.toJavaDuration(startWfAction.getWorkflowRunTimeout()));

      wfOptionsBuilder.setWorkflowTaskTimeout(
          ProtobufTimeUtils.toJavaDuration(startWfAction.getWorkflowTaskTimeout()));

      if (startWfAction.getRetryPolicy() != null) {
        wfOptionsBuilder.setRetryOptions(
            RetryOptionsUtils.toRetryOptions(startWfAction.getRetryPolicy()));
      }

      if (startWfAction.hasMemo()) {
        Map<String, Object> memos = new HashMap<>();
        for (Map.Entry<String, Payload> memo : startWfAction.getMemo().getFieldsMap().entrySet()) {
          EncodedValues encodedMemo =
              new EncodedValues(
                  Optional.of(Payloads.newBuilder().addPayloads(memo.getValue()).build()),
                  dataConverterWithWorkflowContext);
          memos.put(memo.getKey(), encodedMemo);
        }
        wfOptionsBuilder.setMemo(memos);
      }

      if (startWfAction.hasSearchAttributes()) {
        wfOptionsBuilder.setTypedSearchAttributes(
            SearchAttributesUtil.decodeTyped(startWfAction.getSearchAttributes()));
      }

      builder.setOptions(wfOptionsBuilder.build());
      return builder.build();
    }
    throw new IllegalArgumentException("Unsupported action " + action.getActionCase());
  }

  @Nullable
  public SchedulePolicy protoToPolicy(@Nullable SchedulePolicies policy) {
    if (policy == null) {
      return null;
    }
    SchedulePolicy.Builder policyBuilder =
        SchedulePolicy.newBuilder()
            .setPauseOnFailure(policy.getPauseOnFailure())
            .setOverlap(policy.getOverlapPolicy());
    if (policy.hasCatchupWindow()) {
      policyBuilder.setCatchupWindow(ProtobufTimeUtils.toJavaDuration(policy.getCatchupWindow()));
    }
    return policyBuilder.build();
  }

  @Nullable
  public io.temporal.client.schedules.ScheduleState protoToScheduleState(
      @Nullable ScheduleState state) {
    if (state == null) {
      return null;
    }
    return io.temporal.client.schedules.ScheduleState.newBuilder()
        .setNote(state.getNotes())
        .setPaused(state.getPaused())
        .setRemainingActions(state.getRemainingActions())
        .setLimitedAction(state.getLimitedActions())
        .build();
  }

  public io.temporal.client.schedules.Schedule protoToSchedule(Schedule schedule) {
    return io.temporal.client.schedules.Schedule.newBuilder()
        .setAction(protoToAction(schedule.getAction()))
        .setSpec(protoToScheduleSpec(schedule.getSpec()))
        .setPolicy(protoToPolicy(schedule.getPolicies()))
        .setState(protoToScheduleState(schedule.getState()))
        .build();
  }

  public io.temporal.client.schedules.ScheduleInfo protoToScheduleInfo(ScheduleInfo info) {
    return new io.temporal.client.schedules.ScheduleInfo(
        info.getActionCount(),
        info.getMissedCatchupWindow(),
        info.getOverlapSkipped(),
        info.getRunningWorkflowsList().stream()
            .map(wf -> new ScheduleActionExecutionStartWorkflow(wf.getWorkflowId(), wf.getRunId()))
            .collect(Collectors.toList()),
        this.protoToActionResults(info.getRecentActionsList()),
        info.getFutureActionTimesList().stream()
            .map(t -> ProtobufTimeUtils.toJavaInstant(t))
            .collect(Collectors.toList()),
        info.hasCreateTime() ? ProtobufTimeUtils.toJavaInstant(info.getCreateTime()) : null,
        info.hasUpdateTime() ? ProtobufTimeUtils.toJavaInstant(info.getUpdateTime()) : null);
  }
}
