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

package io.temporal.internal.common;

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;

/** Converts history protos into human readable format */
final class HistoryProtoTextUtils {

  private HistoryProtoTextUtils() {}

  public static String toProtoText(History history, boolean showWorkflowTasks) {
    TextFormat.Printer printer = TextFormat.printer();
    StringBuilder result = new StringBuilder();
    for (HistoryEvent event : history.getEventsList()) {
      if (!showWorkflowTasks
          && event.getEventType().name().startsWith("EVENT_TYPE_WORKFLOW_TASK")) {
        continue;
      }
      printEvent(printer, result, event);
      result.append("\n");
    }

    return result.toString();
  }

  private static void printEvent(
      TextFormat.Printer printer, StringBuilder result, HistoryEvent event) {
    event
        .getAllFields()
        .forEach(
            (d, v) -> {
              if (d.getName().endsWith("_attributes")) {
                result.append(d.getName()).append(" { \n");
                String printedAttributes = printEventAttributes(printer, (MessageOrBuilder) v);
                for (String attributeField : printedAttributes.split("\\n")) {
                  result.append("  ").append(attributeField).append('\n');
                }
                result.append("}");
              } else {
                result.append(printer.shortDebugString(d, v));
              }
              result.append("\n");
            });
  }

  private static String printEventAttributes(
      TextFormat.Printer printer, MessageOrBuilder attributesValue) {
    StringBuilder result = new StringBuilder();
    attributesValue
        .getAllFields()
        .forEach(
            (d, v) -> {
              String fieldName = d.getName();
              if (fieldName.equals("input")
                  || fieldName.equals("result")
                  || fieldName.equals("details")) {
                result.append(printer.printFieldToString(d, v));
              } else {
                result.append(printer.shortDebugString(d, v));
                result.append("\n");
              }
            });
    if (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
      // delete trailing \n
      result.deleteCharAt(result.length() - 1);
    }

    return result.toString();
  }
}
