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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

import com.google.common.io.CharStreams;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.testing.WorkflowHistoryLoader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.nio.file.Files;
import org.junit.Test;

public class WorkflowExecutionHistoryTest {
  /**
   * "simpleHistory1_withAddedNewRandomField.json" in comparison with "simpleHistory1.json" contains
   * a new field that is not in the proto schema. Proto allows backwards compatible addition of new
   * fields. This tests verifies that history jsons are also backwards compatible, so an addition of
   * a new unknown field doesn't fail the deserialization.
   */
  @Test
  public void addingANewFieldToHistoryJsonShouldProduceTheSameResult() throws IOException {
    WorkflowExecutionHistory originalHistory =
        WorkflowHistoryLoader.readHistoryFromResource("simpleHistory1.json");
    WorkflowExecutionHistory historyWithAnAddedNewField =
        WorkflowHistoryLoader.readHistoryFromResource(
            "simpleHistory1_withAddedNewRandomField.json");

    assertEquals(originalHistory.getLastEvent(), historyWithAnAddedNewField.getLastEvent());
  }

  @Test
  public void deserializeAndSerializeBackSimpleHistory() throws IOException {
    deserializeAndSerializeBack("simpleHistory1.json");
  }

  @Test
  public void deserializeAndSerializeBackComplexHistory() throws IOException {
    deserializeAndSerializeBack("complexHistory1.json");
  }

  public void deserializeAndSerializeBack(String resourceName) throws IOException {
    // Load legacy-format history
    ClassLoader classLoader = WorkflowExecutionUtils.class.getClassLoader();
    URL resource = classLoader.getResource(resourceName);
    String historyUrl = resource.getFile();
    File historyFile = new File(historyUrl);
    String originalSerializedJsonHistory;
    try (Reader reader = Files.newBufferedReader(historyFile.toPath(), UTF_8)) {
      originalSerializedJsonHistory = CharStreams.toString(reader);
    }
    originalSerializedJsonHistory = originalSerializedJsonHistory.replace("\r\n", "\n");

    // Confirm original history is legacy format
    assertTrue(
        originalSerializedJsonHistory.contains("\"eventType\": \"WorkflowExecutionStarted\""));
    assertFalse(
        originalSerializedJsonHistory.contains(
            "\"eventType\": \"EVENT_TYPE_WORKFLOW_EXECUTION_STARTED\""));

    WorkflowExecutionHistory history = WorkflowHistoryLoader.readHistoryFromResource(resourceName);

    // Confirm serialized to old matches char-for-char
    assertEquals(originalSerializedJsonHistory, history.toJson(true, true));

    // Confirm can convert to new-format
    String newFormatSerializedHistory = history.toJson(true);
    assertFalse(newFormatSerializedHistory.contains("\"eventType\": \"WorkflowExecutionStarted\""));
    assertTrue(
        newFormatSerializedHistory.contains(
            "\"eventType\": \"EVENT_TYPE_WORKFLOW_EXECUTION_STARTED\""));

    // And that new format is parsed correctly
    WorkflowExecutionHistory newFormatHistory =
        WorkflowExecutionHistory.fromJson(newFormatSerializedHistory);
    assertEquals(history.getHistory(), newFormatHistory.getHistory());
  }
}
