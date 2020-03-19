/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Modifications copyright (C) 2020 Temporal Technologies, Inc.
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

package io.temporal.internal.replay;

import static junit.framework.TestCase.assertEquals;

import com.google.protobuf.ByteString;
import io.temporal.converter.DataConverter;
import io.temporal.converter.JsonDataConverter;
import io.temporal.proto.common.SearchAttributes;
import io.temporal.proto.common.WorkflowExecutionStartedEventAttributes;
import io.temporal.workflow.WorkflowUtils;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class WorkflowContextTest {

  @Test
  public void TestMergeSearchAttributes() {
    WorkflowExecutionStartedEventAttributes startAttr =
        WorkflowExecutionStartedEventAttributes.getDefaultInstance();
    WorkflowContext workflowContext = new WorkflowContext("domain", null, startAttr, null);

    DataConverter converter = JsonDataConverter.getInstance();
    Map<String, ByteString> indexedFields = new HashMap<>();
    indexedFields.put("CustomKeywordField", ByteString.copyFrom(converter.toData("key")));

    SearchAttributes searchAttributes =
        SearchAttributes.newBuilder().putAllIndexedFields(indexedFields).build();

    workflowContext.mergeSearchAttributes(searchAttributes);

    assertEquals(
        "key",
        WorkflowUtils.getValueFromSearchAttributes(
            workflowContext.getSearchAttributes(), "CustomKeywordField", String.class));
  }
}
