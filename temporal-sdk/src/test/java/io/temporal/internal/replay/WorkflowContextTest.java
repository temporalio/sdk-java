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

package io.temporal.internal.replay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes;
import io.temporal.internal.common.SearchAttributesUtil;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class WorkflowContextTest {

  @Test
  public void testMergeSearchAttributes() {
    WorkflowExecutionStartedEventAttributes startAttr =
        WorkflowExecutionStartedEventAttributes.getDefaultInstance();
    WorkflowContext workflowContext =
        new WorkflowContext(
            "namespace", WorkflowExecution.getDefaultInstance(), startAttr, 0, null);

    Map<String, Object> indexedFields = new HashMap<>();
    indexedFields.put("CustomKeywordField", "key");

    SearchAttributes searchAttributes = SearchAttributesUtil.encode(indexedFields);

    workflowContext.mergeSearchAttributes(searchAttributes);

    SearchAttributes decodedAttributes = workflowContext.getSearchAttributes();
    assertNotNull(decodedAttributes);

    assertEquals(
        "key", SearchAttributesUtil.decode(decodedAttributes).get("CustomKeywordField").get(0));
  }
}
