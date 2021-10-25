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

import static org.junit.Assert.assertEquals;

import java.io.IOException;
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
        WorkflowExecutionUtils.readHistoryFromResource("simpleHistory1.json");
    WorkflowExecutionHistory historyWithAnAddedNewField =
        WorkflowExecutionUtils.readHistoryFromResource(
            "simpleHistory1_withAddedNewRandomField.json");

    assertEquals(originalHistory.getLastEvent(), historyWithAnAddedNewField.getLastEvent());
  }
}
