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

package com.uber.cadence.internal.common;

import static junit.framework.TestCase.assertEquals;

import com.uber.cadence.SearchAttributes;
import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.workflow.WorkflowUtils;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class InternalUtilsTest {

  @Test
  public void testConvertMapToSearchAttributes() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    String value = "keyword";
    attr.put("CustomKeywordField", value);

    SearchAttributes result = InternalUtils.convertMapToSearchAttributes(attr);
    assertEquals(
        value,
        WorkflowUtils.getValueFromSearchAttributes(result, "CustomKeywordField", String.class));
  }

  @Test(expected = DataConverterException.class)
  public void testConvertMapToSearchAttributesException() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    attr.put("InvalidValue", new FileOutputStream("dummy"));
    InternalUtils.convertMapToSearchAttributes(attr);
  }
}
