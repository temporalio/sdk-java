/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.common;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.uber.cadence.EventType;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.WorkflowExecution;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;

/** Contains workflow execution ids and the history */
public final class WorkflowExecutionHistory {
  private final String workflowId;
  private final String runId;
  private final List<HistoryEvent> events;

  public WorkflowExecutionHistory(String workflowId, String runId, List<HistoryEvent> events) {
    this.workflowId = workflowId;
    this.runId = runId;
    checkHistory(events);
    this.events = ImmutableList.copyOf(events);
  }

  public WorkflowExecutionHistory(WorkflowExecution workflowExecution, List<HistoryEvent> events) {
    this.workflowId = workflowExecution.getWorkflowId();
    this.runId = workflowExecution.getRunId();
    checkHistory(events);
    this.events = ImmutableList.copyOf(events);
  }

  public static WorkflowExecutionHistory fromJson(String serialized) {
    GsonBuilder gsonBuilder = new GsonBuilder();
    gsonBuilder.registerTypeAdapter(ByteBuffer.class, new ByteBufferJsonDeserializer());
    Gson gson = gsonBuilder.create();
    WorkflowExecutionHistory result = gson.fromJson(serialized, WorkflowExecutionHistory.class);
    checkHistory(result.getEvents());
    return result;
  }

  private static void checkHistory(List<HistoryEvent> events) {
    if (events == null || events.size() == 0) {
      throw new IllegalArgumentException("Empty history");
    }
    HistoryEvent startedEvent = events.get(0);
    if (startedEvent.getEventType() != EventType.WorkflowExecutionStarted) {
      throw new IllegalArgumentException(
          "First event is not WorkflowExecutionStarted but " + startedEvent);
    }
    if (startedEvent.getWorkflowExecutionStartedEventAttributes() == null) {
      throw new IllegalArgumentException("First event is corrupted");
    }
  }

  public String toJson() {
    GsonBuilder gsonBuilder = new GsonBuilder();
    Gson gson = gsonBuilder.create();
    return gson.toJson(this);
  }

  public String getWorkflowId() {
    return workflowId;
  }

  public String getRunId() {
    return runId;
  }

  public WorkflowExecution getWorkflowExecution() {
    return new WorkflowExecution().setWorkflowId(workflowId).setRunId(runId);
  }

  public List<HistoryEvent> getEvents() {
    return events;
  }

  private static final class ByteBufferJsonDeserializer
      implements JsonDeserializer<ByteBuffer>, JsonSerializer<ByteBuffer> {

    @Override
    public JsonElement serialize(ByteBuffer value, Type type, JsonSerializationContext ctx) {
      if (value.arrayOffset() > 0) {
        throw new IllegalArgumentException("non zero value array offset: " + value.arrayOffset());
      }
      return new JsonPrimitive(Base64.getEncoder().encodeToString(value.array()));
    }

    @Override
    public ByteBuffer deserialize(JsonElement e, Type type, JsonDeserializationContext ctx)
        throws JsonParseException {
      return ByteBuffer.wrap(Base64.getDecoder().decode(e.getAsString()));
    }
  }
}
