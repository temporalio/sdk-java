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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;

import com.google.common.io.CharStreams;
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
    ClassLoader classLoader = WorkflowExecutionUtils.class.getClassLoader();
    URL resource = classLoader.getResource(resourceName);
    String historyUrl = resource.getFile();
    File historyFile = new File(historyUrl);
    String originalSerializedJsonHistory;
    try (Reader reader = Files.newBufferedReader(historyFile.toPath(), UTF_8)) {
      originalSerializedJsonHistory = CharStreams.toString(reader);
    }

    WorkflowExecutionHistory history = WorkflowHistoryLoader.readHistoryFromResource(resourceName);

    String serializedHistory = history.toJson(true);
    assertEquals(originalSerializedJsonHistory, serializedHistory);
  }
}
