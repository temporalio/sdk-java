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

import static junit.framework.TestCase.assertEquals;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.common.converter.DataConverterException;
import io.temporal.common.converter.SearchAttributesUtil;
import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class InternalUtilsTest {

  private static Map<String, Object> searchAttributes = new HashMap<>();

  public static final String CUSTOM_KEYWORD_FIELD = "CustomKeywordField";
  public static final String KEYWORD_VALUE = "test";
  public static final String CUSTOM_INT_FIELD = "CustomIntField";
  public static final int INT_VALUE = 1;
  public static final String CUSTOM_INTEGER_FIELD = "CustomIntegerField";
  public static final Integer INTEGER_VALUE = 12;
  public static final String CUSTOM_DOUBLE_FIELD = "CustomDoubleField";
  public static final double DOUBLE_VALUE = 1.0;
  public static final String CUSTOM_BOOL_FIELD = "CustomBoolField";
  public static final boolean BOOL_VALUE = true;
  public static final String CUSTOM_BOOLEAN_FIELD = "CustomBooleanField";
  public static final Boolean BOOLEAN_VALUE = false;
  public static final String CUSTOM_DATETIME_FIELD = "CustomDatetimeField";
  public static final LocalDateTime DATETIME_VALUE = LocalDateTime.now();

  @Before
  public void setUp() {
    // add more type to test
    searchAttributes = new HashMap<>();
    searchAttributes.put(CUSTOM_KEYWORD_FIELD, KEYWORD_VALUE);
    searchAttributes.put(CUSTOM_INT_FIELD, INT_VALUE);
    searchAttributes.put(CUSTOM_INTEGER_FIELD, INTEGER_VALUE);
    searchAttributes.put(CUSTOM_DOUBLE_FIELD, DOUBLE_VALUE);
    searchAttributes.put(CUSTOM_BOOL_FIELD, BOOL_VALUE);
    searchAttributes.put(CUSTOM_BOOLEAN_FIELD, BOOLEAN_VALUE);
    searchAttributes.put(CUSTOM_DATETIME_FIELD, DATETIME_VALUE);
  }

  @Test
  public void testConvertMapToSearchAttributes() {
    SearchAttributes searchAttr = SearchAttributesUtil.encode(searchAttributes);
    Map<String, Object> deserializedSearchAttr = SearchAttributesUtil.decode(searchAttr);
    for (Map.Entry<String, Object> entry : deserializedSearchAttr.entrySet()) {
      assertEquals(searchAttributes.get(entry.getKey()), entry.getValue());
    }
  }

  @Test(expected = DataConverterException.class)
  public void testConvertMapToSearchAttributesException() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    attr.put("InvalidValue", new FileOutputStream("dummy"));
    SearchAttributesUtil.encode(attr);
  }
}
