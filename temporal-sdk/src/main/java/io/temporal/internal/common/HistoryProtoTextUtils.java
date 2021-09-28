/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
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

package io.temporal.internal.common;

import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;

/** Converts history protos into human readable format */
public class HistoryProtoTextUtils {

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
              if (d.getName().equals("input") || d.getName().equals("result")) {
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
