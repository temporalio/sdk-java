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

package com.uber.cadence.converter;

import static org.junit.Assert.assertEquals;

import com.uber.cadence.History;
import com.uber.cadence.HistoryEvent;
import com.uber.cadence.TaskList;
import com.uber.cadence.WorkflowExecutionStartedEventAttributes;
import com.uber.cadence.WorkflowType;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class JsonDataConverterTest {

  private final DataConverter converter = JsonDataConverter.getInstance();

  @Test
  public void testThrift() {
    List<HistoryEvent> events = new ArrayList<>();
    WorkflowExecutionStartedEventAttributes started =
        new WorkflowExecutionStartedEventAttributes()
            .setExecutionStartToCloseTimeoutSeconds(11)
            .setIdentity("testIdentity")
            .setInput("input".getBytes(StandardCharsets.UTF_8))
            .setWorkflowType(new WorkflowType().setName("workflowType1"))
            .setTaskList(new TaskList().setName("taskList1"));
    events.add(
        new HistoryEvent()
            .setTimestamp(1234567)
            .setEventId(321)
            .setWorkflowExecutionStartedEventAttributes(started));
    History history = new History().setEvents(events);
    byte[] converted = converter.toData(history);
    History fromConverted = converter.fromData(converted, History.class, History.class);
    assertEquals(new String(converted, StandardCharsets.UTF_8), history, fromConverted);
  }

  public static void foo(List<UUID> arg) {}

  @Test
  public void testUUIDList() throws NoSuchMethodException {
    Method m = JsonDataConverterTest.class.getDeclaredMethod("foo", List.class);
    Type arg = m.getGenericParameterTypes()[0];

    List<UUID> list = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      list.add(UUID.randomUUID());
    }
    DataConverter converter = JsonDataConverter.getInstance();
    byte[] data = converter.toData(list);
    @SuppressWarnings("unchecked")
    List<UUID> result = (List<UUID>) converter.fromDataArray(data, arg)[0];
    assertEquals(result.toString(), list, result);
  }
}
