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

package io.temporal.internal.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.temporal.api.common.v1.SearchAttributes;
import io.temporal.internal.common.SearchAttributesUtil;
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
    this.context = DummySyncWorkflowContext.newDummySyncWorkflowContext();
    this.context.setReplayContext(mockReplayWorkflowContext);
  }

  @Test
  public void testUpsertSearchAttributes() {
    Map<String, Object> attr = new HashMap<>();
    attr.put("CustomKeywordField", "keyword");
    SearchAttributes serializedAttr = SearchAttributesUtil.encode(attr);

    context.upsertSearchAttributes(attr);
    verify(mockReplayWorkflowContext, times(1)).upsertSearchAttributes(serializedAttr);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testUpsertSearchAttributesException() {
    Map<String, Object> attr = new HashMap<>();
    context.upsertSearchAttributes(attr);
  }
}
