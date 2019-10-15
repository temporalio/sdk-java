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

package com.uber.cadence.internal.sync;

import static junit.framework.TestCase.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.uber.cadence.SearchAttributes;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.internal.replay.DecisionContext;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class SyncDecisionContextTest {
  SyncDecisionContext context;
  DecisionContext mockDecisionContext = mock(DecisionContext.class);;

  @Before
  public void setUp() {
    this.context =
        new SyncDecisionContext(
            mockDecisionContext, JsonDataConverter.getInstance(), (next) -> next, null);
  }

  @Test
  public void testUpsertSearchAttributes() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    attr.put("CustomKeywordField", "keyword");
    SearchAttributes serializedAttr = context.convertMapToSearchAttributes(attr);

    context.upsertSearchAttributes(attr);
    verify(mockDecisionContext, times(1)).upsertSearchAttributes(serializedAttr);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUpsertSearchAttributesException() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    context.upsertSearchAttributes(attr);
  }

  @Test
  public void testConvertMapToSearchAttributes() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    String value = "keyword";
    attr.put("CustomKeywordField", value);

    SearchAttributes result = context.convertMapToSearchAttributes(attr);
    assertEquals(value, getKeywordFromSearchAttribute(result));
  }

  @Test(expected = DataConverterException.class)
  public void testConvertMapToSearchAttributesException() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    attr.put("InvalidValue", new FileOutputStream("dummy"));
    context.convertMapToSearchAttributes(attr);
  }

  private static String getKeywordFromSearchAttribute(SearchAttributes searchAttributes) {
    Map<String, ByteBuffer> map = searchAttributes.getIndexedFields();
    ByteBuffer byteBuffer = map.get("CustomKeywordField");
    DataConverter dataConverter = JsonDataConverter.getInstance();
    final byte[] valueBytes = new byte[byteBuffer.limit() - byteBuffer.position()];
    byteBuffer.get(valueBytes, 0, valueBytes.length);
    return dataConverter.fromData(valueBytes, String.class, String.class);
  }
}
