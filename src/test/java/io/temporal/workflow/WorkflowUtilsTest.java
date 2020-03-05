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

package com.uber.cadence.workflow;

import static junit.framework.TestCase.assertEquals;

import com.uber.cadence.SearchAttributes;
import com.uber.cadence.internal.common.InternalUtils;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class WorkflowUtilsTest {

  @Test
  public void TestGetValueFromSearchAttributes() {
    assertEquals(null, WorkflowUtils.getValueFromSearchAttributes(null, "key", String.class));
    assertEquals(
        null, WorkflowUtils.getValueFromSearchAttributes(new SearchAttributes(), "", String.class));

    Map<String, Object> attr = new HashMap<>();
    String stringVal = "keyword";
    attr.put("CustomKeywordField", stringVal);
    Integer intVal = 1;
    attr.put("CustomIntField", intVal);
    Double doubleVal = 1.0;
    attr.put("CustomDoubleField", doubleVal);
    Boolean boolVal = Boolean.TRUE;
    attr.put("CustomBooleanField", boolVal);
    SearchAttributes searchAttributes = InternalUtils.convertMapToSearchAttributes(attr);

    assertEquals(
        stringVal,
        WorkflowUtils.getValueFromSearchAttributes(
            searchAttributes, "CustomKeywordField", String.class));
    assertEquals(
        intVal,
        WorkflowUtils.getValueFromSearchAttributes(
            searchAttributes, "CustomIntField", Integer.class));
    assertEquals(
        doubleVal,
        WorkflowUtils.getValueFromSearchAttributes(
            searchAttributes, "CustomDoubleField", Double.class));
    assertEquals(
        boolVal,
        WorkflowUtils.getValueFromSearchAttributes(
            searchAttributes, "CustomBooleanField", Boolean.class));
  }

  @Test
  public void TestGetValueFromSearchAttributesRepeated() {
    Map<String, Object> attr = new HashMap<>();
    String stringVal = "keyword";
    attr.put("CustomKeywordField", stringVal);
    SearchAttributes searchAttributes = InternalUtils.convertMapToSearchAttributes(attr);
    assertEquals(
        stringVal,
        WorkflowUtils.getValueFromSearchAttributes(
            searchAttributes, "CustomKeywordField", String.class));
    assertEquals(
        stringVal,
        WorkflowUtils.getValueFromSearchAttributes(
            searchAttributes, "CustomKeywordField", String.class));
  }
}
