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

package io.temporal.internal.sync;

import static org.mockito.Mockito.*;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.InternalUtils;
import io.temporal.internal.replay.ReplayWorkflowContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class SyncWorkflowContextTest {
  SyncWorkflowContext context;
  ReplayWorkflowContext mockReplayWorkflowContext = mock(ReplayWorkflowContext.class);

  @Before
  public void setUp() {
    this.context =
        new SyncWorkflowContext(
            mockReplayWorkflowContext, DataConverter.getDefaultInstance(), null, null);
  }

  @Test
  public void testUpsertSearchAttributes() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    attr.put("CustomKeywordField", "keyword");
    SearchAttributes serializedAttr =
        InternalUtils.convertMapToSearchAttributes(attr, DataConverter.getDefaultInstance());

    context.upsertSearchAttributes(attr);
    verify(mockReplayWorkflowContext, times(1)).upsertSearchAttributes(serializedAttr);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUpsertSearchAttributesException() throws Throwable {
    Map<String, Object> attr = new HashMap<>();
    context.upsertSearchAttributes(attr);
  }
}
